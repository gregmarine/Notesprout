# Paper — Extensions arc 3: the HandwritingRecognizer point + the ML Kit extension

> **This file is the project's memory across sessions for arc 3.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase tasks,
> tests, status — is here or in the files this document points at. If it isn't written down here (or
> in the repo / project memory), it doesn't exist. **Read this file top to bottom at the start of
> every session**, after `PAPER_PLAN.md` (v0 — architecture), `PAPER_EXTENSIONS_PLAN.md` (arc 1 —
> the extension API v1 + Templates extension, frozen), `PAPER_NAMING_PLAN.md` (arc 2 — the extension
> store + Naming extension, frozen) and both `CLAUDE.md` files. `docs/extensions.md` is the subsystem
> reference all three arcs write into.
>
> **Status: M0 ✅ (2026-08-17, user-verified SNN + NA5C) · M1 ✅ (2026-08-17, user-verified SNN + NA5C; MIP11 not attached) · M2 ✅ (2026-08-17; automated device run SNN + NA5C + MIP11 + user-verified dot handling on SNN). ARC COMPLETE — this file is frozen.**

## Why

Arcs 1 and 2 settled *how an extension is shaped* (separate APK, AIDL, same-signature trust, the host
draws every UI) and *where an extension keeps data* (the host-owned encrypted store). Arc 3 settles
the third question: **how one extension provides a capability that other extensions consume** — "an
extension for other extensions."

The capability is **handwriting recognition**. The original Notesprout bakes Google ML Kit Digital Ink
Recognition into the app (`apps/notesprout_android/.../recognition/`); Paper's core is "paper with
strokes" and must never learn what handwriting *says*. So recognition becomes an **extension point**
(`HANDWRITING_RECOGNIZER`, engine-neutral — a TrOCR or Onyx-firmware extension can implement it
later) and the **ML Kit extension** (`NSE · ML Kit`) is its first implementation. Later extensions
(search, page text, naming from page text, …) will consume the recognizer **through the core**, never
by binding it themselves.

Nothing consumes it yet. In this arc the only caller is a **debug-build-only** action on the notebook
screen — "Recognize page (ML Kit)" — that hands the current page's strokes to the extension and shows
the recognized text in a popup. That is the arc's test surface; the release build has no user-visible
change at all.

There is still no Extensions UI; extensions are installed and removed by hand.

---

## Working protocol

Identical to `PAPER_NAMING_PLAN.md` §"Working protocol" — each phase in a **fresh session**:

1. **Phase start (no-assumption QA):** read this file (all of it), `PAPER_EXTENSIONS_PLAN.md` and
   `PAPER_NAMING_PLAN.md` (Locked decisions + Architecture + Appendices), `docs/extensions.md`, the root
   `CLAUDE.md`, and `apps/notesprout_paper/CLAUDE.md`. Confirm the next `⬜` phase with the user, flip
   it to `🔄`, then **ask the phase's "Questions to resolve at phase start"** one at a time in the
   wizard (option-select) format before writing code — recommended default first, plus "Other". Do
   not assume answers. If a new ambiguity surfaces mid-phase that would materially change the work,
   stop and ask.
2. **Code** — auto mode; inline; frugal with agents; no Gradle dependency beyond Appendix B without
   asking; deliverables **exactly as written** — no added scope, no "improving" adjacent code, no
   scaffolding for later phases.
3. **Test** — `./gradlew testDebugUnitTest` (all modules), then build + install the debug APKs on the
   requested **test devices** and hand the user the phase's numbered on-device checklist (copy it,
   don't invent). EPD overlays are invisible to screencap — the user verifies by eye and reports.
4. **Fix → test again** until every test passes (JVM + user-reported device checklist).
5. **Docs / memory / CLAUDE.md** — `apps/notesprout_paper/CLAUDE.md` (standing rules + build facts
   only), `docs/extensions.md` (+ `docs/notebook.md` where named), this file's status marker +
   **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_recognition.md` + its
   `MEMORY.md` index line).
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker **the moment the state changes**.

**Test devices** (user verifies by eye; always `-s <serial>`; never install on a device the user didn't
ask for; offline → say so and wait). **The ML Kit model download needs Wi-Fi once per device.**

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

**Model note:** Fable 5 recommended for **M0** (contract design that later consumers inherit +
the segmenter port + ML Kit lifecycle) and **M1** (the shared bind-path extraction touches two
verified security paths); Opus is fine for M2. Either model follows the phase-start question ritual.

---

## Locked decisions (planning Q&A, 2026-08-17)

| Area | Decision |
|---|---|
| How other extensions reach the recognizer (Q1) | **Core-brokered capability binder** — the pattern of `IExtensionStore`: the core discovers and binds the recognizer itself; when the core calls a *future* extension that needs text, it hands that extension a **per-bind, uid-bound, revocable proxy** implementing the same `IHandwritingRecognizer` interface as an in-parameter. Extensions never see each other; trust, timeouts and caps stay in the core. **This arc builds the point + the core client only**; the proxy is *designed for* (the recognizer AIDL is exactly the interface the proxy will implement — no extra contract type) but **wired only when a consumer exists** (a later arc). Rejected: core-only-results-as-data (every consumer pre-planned by the core); direct extension-to-extension binding (breaks host-only trust, bypasses the core). |
| Outward payload (Q2 — the widening of audit row 3) | What the original app hands its engine, and nothing more: **per stroke, x/y point arrays (page px)**; for a page call the **page size**; for an ink call the **writing-area size + a pre-context string**. Never: stroke ids, notebook/page ids, names, colour, width, style, pressure, tilt, timestamps, keys, paths. **This is the first point that sends ink to an extension** — recorded as the explicit widening of row 3 for this point only. |
| Who segments a page into lines (Q2) | **The extension.** `recognizePage` receives the whole page's geometry; the ML Kit extension carries a **verbatim port of the original `StrokeSegmenter`** (vertical projection profile → writing bands; fragment merge; paragraph gap) and does one ML Kit call per line with pre-context chaining, returning the page's text. The core gains no HWR/layout knowledge. |
| Timestamps (Q3) | **None** — points are x/y only (the original app sends the same; Paper's format-B blob has no time channel and this arc makes no `.soil` change). A time channel may be appended to `InkStroke` later as a compatible parcel change. |
| The model (Q4) | **Explicit `status()` + `prepare()`; en-US only.** `status()` → `READY / NEEDS_DOWNLOAD / DOWNLOADING / UNAVAILABLE`; `prepare()` starts the ~20 MB download and returns at once; `recognize*` fails cleanly (`IllegalStateException`) when not ready. The debug action checks status first: not ready → toast "Downloading recognition model…" + `prepare`, the user retries later. Language hardcoded `en-US` in v1 (device-locale / configurable = a later arc; needs the store). |
| Where the model lives (Q5) | **In the extension's own app storage, managed by ML Kit** — the recorded exception to "extensions keep data in the host store": engine assets / downloaded models are **not user data** (and exceed the 256 KiB value cap) and may live in the extension's sandbox; user data still goes to the host store only. Uninstalling the extension removes the model. |
| Debug entry point + popup (Q6) | **Debug-build-only ⋯ on the notebook top bar** (twin of the library `DebugMenu`; a no-op object in `src/release`), action **"Recognize page (ML Kit)"**, **hidden when no recognizer extension is installed**. Runs on the current page's in-memory strokes (`paper.getStrokes()`), toast "Recognizing…" meanwhile; result = `AlertDialog` with the recognized text (selectable) + **Copy** + OK; empty page → toast "Nothing to recognize"; failure → toast with the reason class (never the text). |
| Names / ids (Q7) | Extension point **`HANDWRITING_RECOGNIZER`** (`IHandwritingRecognizer.aidl`, engine-neutral). Extension label **`NSE · ML Kit`** (debug `NSE · ML Kit Dev`); `applicationId com.symmetricalpalmtree.notesprout.ext.mlkit` (debug `.dev`); module **`:ext-mlkit`**; Tabler `puzzle` icon (arc-1 convention). |
| Contract shape (Q8) | `status()`, `prepare()`, **`recognizePage(strokes, pageW, pageH)`** (extension segments + chains pre-context; what the debug test uses) and **`recognizeInk(strokes, areaW, areaH, preContext)`** (one writing area, no segmentation — the primitive a future consumer wants for a lasso'd selection or a heading). Both return plain text (`String`, may be empty). Hand-written parcelable **`InkStroke(x: FloatArray, y: FloatArray)`**. |
| Ink transport + caps (Q9) | **Plain parcel** (`List<InkStroke>` in the transaction) with hard caps **`MAX_INK_STROKES = 2 000`** and **`MAX_INK_POINTS = 60 000`** (≈ 480 KB of floats, under the ~1 MB Binder buffer) enforced **host-side before the call** and **re-checked extension-side**. Over the cap → the core fails the call (debug: toast "Page too dense to recognize"). A `SharedMemory` transport is a later compatible change if a real page ever hits it. |
| Dependency + budgets (Q10) | **`com.google.mlkit:digital-ink-recognition:19.0.0` in `:ext-mlkit` ONLY** — never `:app`, never `:extension-api` (the same artifact + version the original app ships; proven on Nomad/BOOX, no Play Services needed). Timeouts: bind ≤ 3 s (as always) · `status`/`prepare` ≤ 2 s · `recognizeInk` ≤ 10 s · `recognizePage` ≤ **30 s** (one ML Kit call per line; the first call after process start also loads the model). |
| Phases + devices (Q11) | **3 phases**: M0 contract + `:ext-mlkit` (no host change) · M1 host client + shared bind path + notebook debug ⋯ + result dialog · M2 review, boundary audit rows, docs freeze. Verified on **SNN + NA5C + MIP11**. |
| Segmenter + logging (Q12) | **Verbatim port** of `StrokeSegmenter` into `:ext-mlkit` (`RectF` → a tiny pure `Box`, `LiveStroke` → `InkStroke`, same constants: `PARA_GAP_FRAC 0.9`, `BAND_COVERAGE_FRAC 0.15`, fragment merge `3` strokes / `0.4` overlap), JVM-tested; median line height as the `WritingArea` height; pre-context = tail ≤ 20 chars of the previous line; output = lines joined by `\n`, paragraphs by a blank line. **Recognized text is never logged** on either side — counts only (the original app's rule). |
| Shared bind path (Q13) | **Extract it in M1**: one `ExtensionBinder.call(...)` in `:app` `extension/`; `TemplateProviderClient`, `NamerClient` and the new `RecognizerClient` all use it (N2's deferred item, due now that the third point exists). E1 items 1–3 + the N1 checklist subset are re-run in M1/M2 as regression. |
| Trust / artifacts / version | Unchanged: same-signature only, debug-only APKs, no version bump, no `.soil` / index schema change. `ExtensionContract.API_VERSION` stays **1** (new point = new action + new AIDL + same meta-data key). No store for this point (stateless; en-US hardcoded). |

## Deferred (recorded 2026-08-17, not built in this arc)

- **The proxy handoff** (`RecognizerProxyBinder` — per-bind, uid-bound, revocable, capped, implementing
  `IHandwritingRecognizer` over the core's `RecognizerClient`) — built with the **first consumer
  point**. Its shape is fixed by this arc: identical to `ExtensionStoreBinder` (mint in the consumer
  client's `call`, revoke in the unbind `finally`, gate on `getCallingUid() == extUid && !revoked`,
  re-apply `MAX_INK_*` inward, then forward through `RecognizerClient` with the same timeouts).
- **Language selection** (device locale / per-notebook / configurable) — needs the store + a
  settings surface (Extensions UI arc). `en-US` only until then.
- **Timestamps in `InkStroke`** — a compatible parcel-tail addition if a future engine wants them
  (would also need a format-B time channel).
- **`SharedMemory` ink transport** — only if a real page exceeds `MAX_INK_POINTS`.
- **Structured `recognizePage` result** (lines / paragraphs with bounds) — when a consumer needs
  positions, add a `recognizePageLayout` method (compatible append), keep the text one.
- **A user-facing recognition feature** (page text, search, "copy page as text") — a later arc; this
  arc's only caller is the debug ⋯.
- **Store pre-warm generalisation / fewer namer binds** — still deferred from arc 2 (unchanged).
- **Recognizer warm-up for consumers** (recorded M1): a cold extension process costs ≈ 1.9 s (process
  + client) **plus** the first inference, which is where ML Kit loads the model (4.5 s Nomad / 1.8 s
  NA5C). A consumer that wants a snappy first result must bind early **and** the extension would need
  a warm-up inference at start (a dot stroke after `buildClient`) — not built; the debug ⋯ accepts ~6 s.
- **Restart a stale in-flight download on `prepare()`** (M1): a download interrupted mid-way stays
  "in flight" from `ModelManager`'s view until ML Kit fails it; the host's Download button then only
  re-attaches the progress dialog. Fine for the debug surface; revisit if a real feature needs it.
- ~~**Toast-vs-dialog sweep of arc-2 surfaces**~~ — **done in M2** (`Dialogs.problem`; namer, name and
  move problems are dialogs across the library).

## Non-goals for this arc (do not build, do not scaffold "for later")

No Extensions UI · no third-party trust · no publishing of `:extension-api` · no release-build UI ·
no recognition results stored anywhere (no `page_text` cache, no `.soil` / index change) · no
recognition of anything but the current page's strokes · no other languages · no per-notebook
settings · no proxy binder / consumer point (only its shape is recorded above) · no image OCR /
TrOCR / Onyx engines · no changes to g-paper, `NotebookSession`, `StrokeStore`, `StrokeCodec`, the
Templates or Naming extensions' *behaviour* (the one permitted touch to both is switching their
clients onto the shared bind path in M1) · no release signing · no version bump.

---

## Architecture

### Module layout (after this arc)

```
apps/notesprout_paper/
├── settings.gradle.kts            include(":app", ":extension-api", ":ext-templates", ":ext-naming", ":ext-mlkit")
├── PAPER_RECOGNITION_PLAN.md      this file
├── docs/extensions.md             gains: "HandwritingRecognizer", "The ML Kit extension", audit rows 14–17,
│                                  "Adding a capability point" (the proxy shape, for the future consumer)
├── app/…/notesprout/
│   ├── extension/                 + ExtensionBinder (shared bind path, M1), RecognizerClient,
│   │                              ExtensionRegistry.handwritingRecognizer(); TemplateProviderClient +
│   │                              NamerClient rebased onto ExtensionBinder
│   └── notebook/                  NotebookActivity: debug ⋯ install hook + InkPayload builder;
│       src/debug/…/notebook/NotebookDebugMenu.kt (+ release no-op twin)
├── extension-api/src/main/
│   ├── aidl/…/extension/{IHandwritingRecognizer,InkStroke}.aidl
│   └── kotlin/…/extension/{ExtensionContract (+ constants), InkStroke, RecognizerStatus}.kt
└── ext-mlkit/                     Android APPLICATION, com.symmetricalpalmtree.notesprout.ext.mlkit (.dev)
    └── src/main/
        ├── AndroidManifest.xml    NO launcher activity; one exported <service> (HANDWRITING_RECOGNIZER + API_VERSION meta)
        ├── kotlin/…/ext/mlkit/{HandwritingRecognizerService, MlKitEngine, ModelManager, StrokeSegmenter, Box}.kt
        └── res/                   strings (label), puzzle icon
```

Dependency direction unchanged: `:app → :extension-api`, every `:ext-* → :extension-api`.
`:extension-api` depends on nothing in the project. **ML Kit is a dependency of `:ext-mlkit` only.**

### Contract additions (`:extension-api`) — exact

`ExtensionContract` gains:

| Constant | Value |
|---|---|
| `ACTION_HANDWRITING_RECOGNIZER` | `"com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER"` |
| `MAX_INK_STROKES` | `2_000` — most strokes in one recognize call (host-enforced before the call, re-checked by the extension) |
| `MAX_INK_POINTS` | `60_000` — most points (sum over strokes) in one recognize call |
| `MAX_PRECONTEXT_CHARS` | `20` — the host truncates `preContext` to its tail before the call |
| `MAX_RECOGNIZED_CHARS` | `20_000` — host-side cap on returned text (a page cannot say more; the rest is dropped) |

`RecognizerStatus` (Kotlin `object` of `Int` constants — AIDL carries `int`, no enum in the contract):

| Name | Value | Meaning |
|---|---|---|
| `READY` | `0` | model on device, engine constructed; `recognize*` will run |
| `NEEDS_DOWNLOAD` | `1` | model not on device; call `prepare()` |
| `DOWNLOADING` | `2` | `prepare()` started and the download is in flight |
| `UNAVAILABLE` | `3` | engine cannot run here (identifier unknown, download failed permanently, …) |

AIDL (`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`):

```aidl
// InkStroke.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable InkStroke;

// IHandwritingRecognizer.aidl — the HANDWRITING_RECOGNIZER point (arc 3).
// Engine-neutral. Every argument is bare geometry; every result is plain text. Stateless.
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.InkStroke;
interface IHandwritingRecognizer {
    /** One of RecognizerStatus.* — READY / NEEDS_DOWNLOAD / DOWNLOADING / UNAVAILABLE. Fast. */
    int status();
    /** Start acquiring what the engine needs (model download). Returns at once; poll status(). */
    void prepare();
    /** Recognize one writing area (no layout analysis). [strokes] in the area's px space,
     *  [areaWidth]/[areaHeight] > 0, [preContext] = the text just before this ink ("" if none).
     *  Returns the top candidate ("" if none). Throws IllegalStateException if status() != READY,
     *  IllegalArgumentException over the MAX_INK_* caps. */
    String recognizeInk(in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext);
    /** Recognize a whole page: the engine finds lines / paragraphs itself and chains context.
     *  [strokes] in page px; [pageWidth]/[pageHeight] the page size. Returns lines joined by '\n',
     *  paragraphs separated by a blank line ("" if nothing recognizable). Same exceptions. */
    String recognizePage(in List<InkStroke> strokes, float pageWidth, float pageHeight);
}
```

- **`InkStroke(val x: FloatArray, val y: FloatArray)`** — hand-written Parcelable (`writeInt(n);
  writeFloatArray(x); writeFloatArray(y)`, `@JvmField CREATOR`, `describeContents = 0`);
  `require(x.size == y.size && x.isNotEmpty())`. Nothing else in it — no id, no time, no pressure.
- Everything the host receives is untrusted: `status()` outside `0..3` → treated as `UNAVAILABLE`;
  returned text truncated to `MAX_RECOGNIZED_CHARS`; null → `""`.

### Host side (`:app` `extension/` + `notebook/`)

- Manifest `<queries>` gains `<intent><action android:name="…HANDWRITING_RECOGNIZER"/></intent>`.
- **`ExtensionBinder`** (M1 — the extracted shared bind path): `suspend fun <I, T> call(appContext,
  ref, asInterface: (IBinder) -> I, bindTimeoutMs = 3_000, callTimeoutMs, block: suspend (I) -> T): T`
  — byte-for-byte the verified `NamerClient.call` body: explicit component, `checkSignatures` re-run
  before the bind, `BIND_AUTO_CREATE` on the app context, `CompletableDeferred` connection ≤ bind
  timeout, `block` on IO under `withTimeout` in a supervisor scope (an un-interruptible Binder call
  that outlives its timeout finishes on its own thread and is discarded), unbind in `finally`; the
  same catch ladder → **one** `ExtensionCallException`, `CancellationException` re-thrown. The
  store variant stays in `NamerClient` (pre-open on IO, mint `ExtensionStoreBinder`, revoke in the
  same `finally`) *around* `ExtensionBinder.call`. `TemplateProviderClient` and `NamerClient` lose
  their private copies; their public methods, timeouts and log tags are unchanged.
- `ExtensionRegistry.handwritingRecognizer(ctx): ProviderRef?` — same discovery + trust filter,
  action `ACTION_HANDWRITING_RECOGNIZER`, **first** by (label, package); others `Slog.d` + dropped
  (choosing an engine is Extensions-UI territory).
- **`RecognizerClient(ctx, ref)`** — over `ExtensionBinder`:
  - `status(): Int` (≤ 2 s; outside `0..3` → `UNAVAILABLE`) · `prepare()` (≤ 2 s) ·
    `recognizeInk(strokes, areaW, areaH, preContext): String` (≤ 10 s) ·
    `recognizePage(strokes, pageW, pageH): String` (≤ 30 s).
  - **Caps before the call**: `strokes.size ≤ MAX_INK_STROKES`, `Σ points ≤ MAX_INK_POINTS`, every
    stroke non-empty with equal x/y lengths, `areaW/areaH/pageW/pageH > 0`, `preContext` truncated to
    its last `MAX_PRECONTEXT_CHARS` — violations throw `InkTooLargeException` (an
    `ExtensionCallException` subclass) **without binding**.
  - **Inward**: result `?: ""`, `.take(MAX_RECOGNIZED_CHARS)`; `IllegalStateException` from the
    extension (not ready) → `ExtensionCallException("recognizer not ready")`.
  - No store (stateless point). Log tag `RecognizerClient`: bind/unbind + counts + durations only —
    **never text**.
- **`InkPayload`** (`notebook/`, pure Kotlin, JVM-tested): `fromStrokes(List<Stroke>): List<InkStroke>`
  — g-paper `Stroke` → `InkStroke(x[], y[])`, dropping everything else (id, colour, width, style,
  pressure, tilt, time). The one place page ink is reduced to geometry.
- **`NotebookDebugMenu`** (`src/debug/…/notebook/`, no-op twin in `src/release`): `install(activity,
  topBar, provider: () -> RecognizeContext)` adds a ⋯ `AppCompatImageButton` at the end of the top
  bar (same construction as the library `DebugMenu` — dimen-driven, tooltip, `bg_toolbar_button`);
  the bar's exclusion rect already covers it. Tapping opens an `ActionSheetDialog` "Debug tools":
  - **"Recognize page (ML Kit)"** — present only if `ExtensionRegistry.handwritingRecognizer` found a
    ref (refreshed on each sheet open, IO). Flow: `paper.getStrokes()` (any thread) → empty → toast
    "Nothing to recognize" · `InkPayload.fromStrokes` → `RecognizerClient.status()`: `NEEDS_DOWNLOAD`
    → `prepare()` + toast "Downloading recognition model — try again in a minute" · `DOWNLOADING` →
    toast "Model still downloading…" · `UNAVAILABLE` → toast "Recognizer unavailable on this device" ·
    `READY` → toast "Recognizing…" → `recognizePage(ink, pageW, pageH)` (page size from the session's
    current page px rect) → **`AlertDialog`** titled "Recognized text (ML Kit · N strokes · T ms)",
    message = the text in a selectable `TextView` (`""` → "(nothing recognized)"), buttons **Copy**
    (clipboard, label "Recognized text") + **OK**. `InkTooLargeException` → toast "Page too dense to
    recognize"; any other `ExtensionCallException` → toast "ML Kit extension didn't respond". One
    `recognizeBusy` guard drops a second tap while a call runs. Everything routes through `Dialogs.style`;
    the sheet/dialog are chrome — no pen input while up is not required (the paper stays live; the
    dialog is modal anyway).
  - The debug ⋯ is a **finger** target like the toolbar buttons; `dispatchTouchEvent`'s
    `releaseRender()` over chrome already applies (it is inside `topBar`).

### Extension side (`:ext-mlkit`)

- Gradle mirrors `:ext-naming` (application, `minSdk 29`, `compileSdk`/`targetSdk 35`, `versionCode 1`
  / `0.1.0`, `buildConfig` + `HOST_PACKAGE` per build type, `applicationIdSuffix ".dev"`,
  `allowBackup="false"`, no Activity, label `NSE · ML Kit` (debug ` Dev`), puzzle icon copied) **plus**
  `implementation("com.google.mlkit:digital-ink-recognition:19.0.0")` (Appendix B). Manifest: one
  exported `<service android:name=".HandwritingRecognizerService">` with intent-filter
  `…HANDWRITING_RECOGNIZER` + `<meta-data …API_VERSION android:value="1"/>`.
- **`HandwritingRecognizerService`**: `onBind` → `IHandwritingRecognizer.Stub`; **every** method
  first `HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)`. `status()` / `prepare()` delegate to
  `ModelManager`; `recognizeInk` / `recognizePage` re-check the `MAX_INK_*` caps
  (`IllegalArgumentException`), require `READY` (`IllegalStateException`), then run `MlKitEngine`
  **synchronously on the Binder thread** (`Tasks.await` — the host's timeout is the ceiling; ML Kit's
  own executor does the work). Any engine failure → `IllegalStateException` (Binder-marshalable —
  never let another exception type kill the transaction silently, arc-2 lesson). No text in logs.
- **`ModelManager`** (process-lifetime `object`): the `en-US` `DigitalInkRecognitionModel`;
  `status()` = cached `READY` if the recognizer is built, else `RemoteModelManager.isModelDownloaded`
  (`Tasks.await`, ≤ 1.5 s — inside the host's 2 s) → `READY` after building the client /
  `NEEDS_DOWNLOAD` / `DOWNLOADING` (a `prepare()` task in flight) / `UNAVAILABLE` (identifier null,
  await failure). `prepare()` = `RemoteModelManager.download(model, DownloadConditions())` once
  (idempotent while in flight), returns immediately; on success builds the client and flips to
  `READY`; on failure → `NEEDS_DOWNLOAD` again (retryable) with the cause logged (class only).
  **M0 device experiment (must be recorded in the Outcome):** does a download started by `prepare()`
  finish after the host unbinds (extension process may be killed)? Expected yes if ML Kit hands the
  fetch to the system `DownloadManager`; if not, the mitigation is chosen at M1 Q-time (hold the one
  debug binding while `DOWNLOADING`, or a started service in the extension) — do **not** pre-build it.
- **`MlKitEngine`**: `recognizeInk(strokes, w, h, pre)` builds one `Ink` (`Ink.Point.create(x, y)`
  — no time), `WritingArea(max(w,1), max(h,1))`, `RecognitionContext(preContext = pre.takeLast(20))`,
  `Tasks.await(recognizer.recognize(...))` → `candidates.firstOrNull()?.text ?: ""`.
  `recognizePage(strokes, pageW, pageH)` = `StrokeSegmenter.segment(strokes)` → for each paragraph,
  for each line: `recognizeInk(line.strokes, line.bounds.width, layout.medianLineHeight (fallback
  line height), preContext = tail of the previous recognized line)` → lines joined `\n`, paragraphs
  joined `\n\n`. A line that recognizes to `""` contributes nothing (no "unrecognized" placeholder —
  the original's `FALLBACK_TEXT` is an app-level convention the core doesn't have).
- **`StrokeSegmenter`** (pure Kotlin, JVM-tested) — the original file ported **verbatim**: `RectF` →
  `Box(left, top, right, bottom)` with `width/height/union/overlap`, `LiveStroke` → `InkStroke`,
  `Segment(strokes, bounds)`, `Paragraph(lines)`, `PageLayout(paragraphs, medianLineHeight)`,
  `segment(strokes)`; constants unchanged (`PARA_GAP_FRAC = 0.9f`, `BAND_COVERAGE_FRAC = 0.15f`,
  `FRAGMENT_MAX_STROKES = 3`, `MERGE_OVERLAP_FRAC = 0.4f`). Read
  `apps/notesprout_android/.../recognition/StrokeSegmenter.kt` and
  `docs/handwriting-recognition.md` §"StrokeSegmenter" before porting; behaviour must not drift.

### Rules for adding a future extension point (from arc 1 — followed here)

1. Action + AIDL + parcelables in `:extension-api`; keep the dependency direction. ✔
   (`HANDWRITING_RECOGNIZER`, `IHandwritingRecognizer`, `InkStroke`, `RecognizerStatus`)
2. Discovery in `ExtensionRegistry` (same trust filter) + a client with explicit timeouts,
   bind-per-operation, unbind-in-finally, untrusted-payload caps. ✔ (`handwritingRecognizer`,
   `RecognizerClient` over the shared `ExtensionBinder`)
3. The core decides what the user sees on failure; extensions never show UI in the core's flow. ✔
   (every toast/dialog is the debug menu's)
4. Document in `docs/extensions.md` + boundary-audit rows. ✔ (M2, rows 14–17)
5. Nothing crosses that the call doesn't need. ✔ — with the **explicit, recorded widening**: bare
   stroke geometry (x/y in px) + area/page size + ≤ 20 chars of pre-context, for this point only.

### The capability pattern (recorded now, built with the first consumer)

A **capability point** is an extension point whose implementation the core lends to *other*
extensions. The recipe, fixed by this arc so a consumer arc can't drift from it:

- The capability's AIDL is the interface both the provider **and** the core's proxy implement.
- The core is the only binder of the provider (`RecognizerClient`). A consumer point that needs the
  capability takes it as an **in-parameter** (`IHandwritingRecognizer recognizer`) on the calls that
  need it — exactly like `IExtensionStore`.
- The consumer client mints a **`RecognizerProxyBinder(client, extUid)`** per bind, uid-gated and
  revocable (`ExtensionStoreGate` shape), which re-applies the `MAX_INK_*` caps inward and forwards to
  `RecognizerClient` (own bind, own timeouts, own signature check) — a proxy call is therefore two
  hops and must fit inside the consumer call's timeout; the consumer's timeout is sized accordingly.
- No provider installed → the consumer receives `null` for the parameter (AIDL allows a null
  interface) and must cope; the core never fakes a recognizer.

---

## Phases

### Phase M0 — Contract + the ML Kit extension (no host change)
**Status:** ✅ Complete (commit 7061255; user-verified SNN + NA5C 2026-08-17)

**Goal:** `IHandwritingRecognizer` exists in the contract; `NSE · ML Kit` installs, is discovered
by nothing yet, and can be exercised end-to-end from a JVM test (segmenter) and from adb (`dumpsys`
shows the service; a scratch host-side probe is **not** built — M1 brings the caller). No
user-visible change.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. `RecognizerStatus` as an `object` of `Int` constants (rec. — AIDL-native, no parcelable) / a
   parcelable enum-like class?
2. `recognizePage` when the model is not ready — throw `IllegalStateException` (rec.; the host
   checks `status()` first) / return `""`?
3. Model download conditions — none (rec.: any network, matches the original) / Wi-Fi only?
4. Should `prepare()` be callable while `READY` (rec.: yes, no-op) — trivial, confirm.
5. Segmenter tests — port the original's `StrokeSegmenterTest` if one exists (check
   `apps/notesprout_android/app/src/test/`), else write: single line · two lines with descender
   overlap · paragraph gap · fragment merge · empty input · median line height (rec.: port + fill
   gaps).

**Deliverables**
1. `:extension-api`: `IHandwritingRecognizer.aidl`, `InkStroke.aidl` + Kotlin Parcelable,
   `RecognizerStatus`, `ExtensionContract.ACTION_HANDWRITING_RECOGNIZER` + `MAX_INK_STROKES` /
   `MAX_INK_POINTS` / `MAX_PRECONTEXT_CHARS` / `MAX_RECOGNIZED_CHARS`; `InkStrokeTest` (parcel round
   trip via `Parcel.obtain` is instrumented — so a pure test of the `require`s + a JVM parcel test only
   if `:extension-api` already runs Robolectric-free tests: it doesn't → require-checks only).
2. `settings.gradle.kts` `include(":ext-mlkit")`; `:ext-mlkit` exactly as in "Extension side"
   (Gradle + ML Kit dep, manifest, service, `ModelManager`, `MlKitEngine`, `StrokeSegmenter`, `Box`,
   strings, icon, `HOST_PACKAGE`, debug label override).
3. JVM tests: `:ext-mlkit` `StrokeSegmenterTest` (per Q5), `BoxTest` (union/overlap), a
   `MlKitEngine` **pure** helper test if any pure logic is factored (paragraph/line joining, pre-context
   tail) — the ML Kit calls themselves are not JVM-testable.
4. Docs: `docs/extensions.md` §"HandwritingRecognizer" (contract) + §"The ML Kit extension"
   (model lifecycle, the storage exception, the segmenter); `README.md` install line; `CLAUDE.md`
   build lines for `:ext-mlkit`.

**Tests**
- JVM: `./gradlew testDebugUnitTest` (all modules) green; `assembleDebug` builds five modules.
- Shell sanity per device (Claude runs) after installing `ext-mlkit-debug.apk`: `pm list packages |
  grep ext.mlkit`; `dumpsys package … | grep -A6 HANDWRITING_RECOGNIZER`; `pm resolve-activity --brief
  -c android.intent.category.LAUNCHER <pkg>` → "No activity found"; BOOX: `pm enable`, wait, confirm
  `pm list packages -d`. APK size noted (ML Kit adds several MB).
- **User device checklist** (short — nothing calls it yet):
  1. Settings → Apps shows "NSE · ML Kit Dev" with the puzzle icon; no launcher icon (SNN's sidebar
     shows it, as with the other two — accepted).
  2. Paper still opens/creates/writes notebooks; Templates + Naming unchanged (spot check: New notebook
     with Lined in a scheme folder).
- Claude-side: `logcat -s HandwritingRecognizerService ModelManager` stays silent (nothing binds).

**Close-out:** status ✅ + Outcome (incl. the download-after-unbind experiment result if it can be run
from an adb-driven `am start-service`/`bindService` probe — else deferred to M1's first real
`prepare()`); docs; memory; commit + push.

**Outcome (2026-08-17):**
- **Q&A:** all five recommended defaults taken — `RecognizerStatus` = `object` of `Int`s · not-READY
  throws `IllegalStateException` · download conditions none (any network) · `prepare()` no-op while
  READY/DOWNLOADING · six segmenter cases written (the original has no `StrokeSegmenterTest`).
- **M0 base commit (for M2's review range):** `4cdf74e` (the commit before M0's first commit).
- **Built:** `:extension-api` + `InkStroke`/`RecognizerStatus`/AIDL/constants (4 tests);
  `:ext-mlkit` (`HandwritingRecognizerService`, `ModelManager`, `MlKitEngine` + pure `PageText`,
  `StrokeSegmenter`, `Box`; 15 tests: 8 segmenter · 4 box · 3 page-text). `assembleDebug` builds
  five modules; all-module `testDebugUnitTest` green. `ext-mlkit-debug.apk` = **40 MB** (ML Kit's
  on-device engine; the ~20 MB model downloads separately on first `prepare()`).
- **Port notes:** the segmenter is verbatim except (a) no log line (pure module — the count log
  moved to the service) and (b) the AABB is computed once per stroke via `Box.of` (`InkStroke` has
  no precomputed box). Two of the first-cut fixtures were unrealistic (8-stroke lines with touching
  extents → one band) — the fixtures were made realistic (20-stroke lines / a 10 px gap); the
  algorithm was not touched. `MlKitEngine` bounds each ML Kit call to 10 s (`Tasks.await` timeout)
  so a wedged engine cannot pin a Binder thread; the host's per-call timeout remains the ceiling.
- **Download-after-unbind experiment:** **deferred to M1's first real `prepare()`** — `HostCallerCheck`
  refuses the adb shell uid, so no adb-driven probe can call `prepare()` (and nothing else binds
  the service before M1).
- **Device sanity (SNN + NA5C, 2026-08-17 — Claude):** `ext-mlkit-debug.apk` installs on both;
  `pm list packages` shows `…ext.mlkit.dev`; `dumpsys package` resolves the
  `HANDWRITING_RECOGNIZER` action to `HandwritingRecognizerService`; `pm resolve-activity …LAUNCHER` →
  "No activity found"; NA5C stayed enabled after the `pm enable` dance (`pm list packages -d` empty);
  merged manifest carries `API_VERSION = 1`. **Note:** ML Kit's Play-Services dependency merges a
  non-exported, non-launcher `com.google.android.gms.common.api.GoogleApiActivity` into the APK
  (same as the original app) — the extension still declares no Activity of its own. `logcat -s
  HandwritingRecognizerService ModelManager` silent (nothing binds). MIP11 not attached — not installed (M1 verifies all three).
- **User checklist:** both items pass on SNN + NA5C (label + icon, no launcher icon; Paper +
  Templates + Naming unchanged).

---

### Phase M1 — Host: shared bind path, RecognizerClient, notebook debug ⋯ + result dialog
**Status:** ✅ Complete (commit 409c180; user-verified SNN + NA5C 2026-08-17 — MIP11 not attached, covered by M2's full re-run)

**Goal:** on a debug build with `NSE · ML Kit` installed, the notebook screen's ⋯ → "Recognize page
(ML Kit)" downloads the model on first use and then shows the current page's recognized text in a
dialog. Release builds and devices without the extension are unchanged. Templates and Naming behave
exactly as before on the shared bind path.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Model-download UX in the debug flow — `prepare()` + toast, user retries (rec.) / the debug action
   polls `status()` every 2 s for up to 2 min then recognizes automatically (holds no binding —
   re-binds per poll)? **If M0's experiment showed the download dies on unbind, this question also
   picks the mitigation** (hold the debug binding while `DOWNLOADING` — a debug-only exception to
   bind-per-operation, or an extension-side started service).
2. Result dialog extras — title with `N strokes · T ms` (rec.) / plain "Recognized text"? Show the
   segment/line count too (rec.: yes, in the title)?
3. Where `RecognizerClient` gets the page size — the session's current page px rect (rec.) / the
   strokes' bounding box?
4. `ExtensionBinder` extraction scope — Templates + Naming + Recognizer all on it now (rec., locked
   Q13) — confirm; and log tags stay per-client (rec.).
5. Toast wording — as written in "Host side" (rec.) / other?

**Deliverables**
1. `:app` `extension/ExtensionBinder.kt` (the extracted `call`); `TemplateProviderClient` and
   `NamerClient` rebased onto it with **no behaviour change** (same timeouts, same store handling,
   same tags); `ExtensionRegistry.handwritingRecognizer`; `RecognizerClient` (+
   `InkTooLargeException`); manifest `<queries>` action.
2. `notebook/InkPayload.kt` (pure) + `InkPayloadTest`; `NotebookActivity` installs
   `NotebookDebugMenu` on the top bar (one line, like `LibraryActivity` does) and exposes the current
   page's px size + `paper.getStrokes()` through a small `RecognizeContext` provider; the top-bar
   exclusion rect already covers the new button — verify `pushExclusions()` needs no change.
3. `src/debug/…/notebook/NotebookDebugMenu.kt` + `src/release` no-op twin, exactly as in "Host side"
   (sheet, status ladder, dialog with Copy, `recognizeBusy` guard, toasts); strings in `strings.xml`
   (`debug_*` / `recognize_*`).
4. JVM tests: `RecognizerClient` cap checks factored into a pure `InkCaps.check(strokes, …)` and
   tested (over strokes / over points / empty stroke / mismatched arrays / non-positive size /
   preContext truncation); `InkPayloadTest` (id/colour/pressure dropped, x/y preserved, empty list).
5. Docs: `docs/extensions.md` §"HandwritingRecognizer — host behaviour" (registry, client, caps,
   the debug surface, failure surface) + the shared bind path noted under Host behaviour;
   `docs/notebook.md` (debug ⋯); `CLAUDE.md` build lines.

**Tests**
- JVM green; `assembleDebug` five modules; `assembleRelease` of `:app` still compiles (the no-op twin).
- **User device checklist** — install `app-debug.apk` + `ext-mlkit-debug.apk` (+ keep Templates +
  Naming installed):
  1. Open a notebook: the top bar shows the ⋯ at the end; pen ink never lands under it.
  2. ⋯ → "Recognize page (ML Kit)" on a **blank page** → toast "Nothing to recognize".
  3. Write one line ("hello world") → ⋯ → Recognize → first time (device on Wi-Fi): dialog
     "Recognition model needed" → **Download** → progress dialog with an elapsed-time counter → closes
     by itself → "Recognizing…" popup → dialog with the text (expect `hello world` or close), Copy
     works (paste somewhere), OK dismisses. **No second tap.** (Amended in M1 — see Outcome.)
  4. Write three lines with a blank-line gap before the third → Recognize → three lines, a blank
     line before the third.
  5. Flip to a new page, write a word → Recognize → only that page's word.
  6. Undo a stroke → Recognize → the undone stroke's ink is not recognized.
  7. Airplane mode (model already downloaded) → Recognize → still works (on-device).
  8. `am force-stop …ext.mlkit.dev` → Recognize → works (auto-create; first call slower).
  9. `pm disable-user --user 0 …ext.mlkit.dev` (Claude) → ⋯ sheet has **no** Recognize item;
     `pm enable` → item back, model still present (no re-download).
  10. Regression on the shared bind path: New notebook with **Lined** template creates a lined
      notebook (E1 items 1–3 quick form) · +Folder with scheme `Meeting {date} {n:2}` → +Notebook
      prefilled `Meeting <today> 01` (N1 items 2–3).
  11. Release build sanity is Claude's (`assembleRelease` compiles) — no release install.
- Claude-side log check on SNN: `logcat -s ExtensionRegistry RecognizerClient
  HandwritingRecognizerService ModelManager` → one bind/unbind per call, durations logged, **no
  recognized text in any line**, no `leaked ServiceConnection`; note the first-call (model load) time
  vs. warm.

**Close-out:** status ✅ + Outcome (timings per device, download-after-unbind finding, e-ink notes);
docs; memory; commit + push.

---

**Outcome (2026-08-17):**
- **Q&A:** all five recommended defaults taken at phase start (prepare + toast · title with N strokes
  · T ms · session page size · all three clients on `ExtensionBinder` with per-client tags · plan
  wording). **Q1 was then amended twice on the device**, with the user:
  1. *Finding:* M0's `status()` blocked ≤ 1.5 s on ML Kit's `isModelDownloaded` and reported a timeout
     as `UNAVAILABLE`; on the Nomad the first such check in a fresh process took **~75 s** (later runs
     26–28 s; ~4.6 s once the model exists), so the first taps said "didn't respond" / "unavailable"
     for a model that was one **6 s** download away. → `ModelManager` became **one async ensure-ready
     chain** (check → download → build) started on the service's `onCreate` and by `prepare()`;
     `status()` never blocks; the recognize calls **wait for readiness** inside the host's timeout
     (22 s page / 6 s ink) — the AIDL comment was softened accordingly ("waits, throws only if it
     cannot become ready in time").
  2. *UX:* a toast cannot carry a minute-long one-time event ("it feels like the app isn't doing
     anything"). → the **model dialog flow**: "Recognition model needed" (Download / Cancel; offline
     pre-check via `Connectivity.isOnline` — ML Kit's downloader hangs offline, no error in >1 min) →
     progress dialog with an elapsed-time counter (2 s refresh, e-ink-safe indeterminate; Cancel hides
     it only; "Waiting for a network connection…" + 30 s offline give-up) → auto-recognize on READY.
     Then **model-present memory** in the extension's own prefs so a fresh process is READY at once
     (without it, a cold process reported `DOWNLOADING` during the check and the host offered a
     download for a model already on disk — checklist #8 caught it). Then **"Recognizing…" as an
     Opening-style popup**, and every "why nothing happened" message as a **dialog** (rule recorded in
     `docs/notebook.md`; also applied to `NewNotebookActivity`'s taken/invalid-name and template
     failures — the taken-name toast was missed during checklist #10 and looked like a failed create).
- **Built (host):** `ExtensionBinder` (all three clients on it, no behaviour change; the namer's store
  binder is revoked in the client's own `finally` right after the shared unbind), `RecognizerClient` +
  `RecognizerNotReadyException` + `InkCaps` (9 tests) + `InkTooLargeException`,
  `ExtensionRegistry.handwritingRecognizer`, manifest query + `ACCESS_NETWORK_STATE`,
  `notebook/InkPayload` + `RecognizeContext` (4 tests), `core/Connectivity`, `NotebookDebugMenu`
  (+ release twin), `topBarRow` id, strings. **Extension:** `ModelManager` chain + `awaitReady` +
  model-present flag + `onEngineFailure`; service `onCreate` warm-up + readiness waits.
- **Download-after-unbind:** the download **survives** the host's unbind (the extension process stayed
  cached across every unbind — same pid for minutes; the chain also continues while the progress
  dialog is cancelled). No mitigation needed.
- **Timings:** warm page call ≈ 0.5 s (Nomad) / cold extension process ≈ 6 s Nomad, ≈ 4 s NA5C
  (first inference loads the model); model download 6 s on Wi-Fi; ML Kit's cold `isModelDownloaded`
  75 s → 28 s → 4.6 s across runs (network-bound the first time).
- **Device checklist:** items 1–10 pass on SNN and NA5C (BOOX re-disabled the *app* after two of the
  installs — the standing trap; the enable dance fixed it). Unhappy paths on SNN: offline before
  Download → offline dialog; offline mid-download → waiting message → "Download failed" at 30 s;
  Cancel → later Recognize resumes. Log check on both: one bind/unbind per call, counts + durations
  only, **no recognized text**, no `leaked ServiceConnection`. MIP11 was not attached — M2's full
  re-run covers it.
- **Deferred from M1:** see the Deferred list (consumer warm-up, stale-download restart, arc-2
  toast sweep).

---

### Phase M2 — Hardening, review, boundary audit, docs freeze
**Status:** ✅ Complete (commit hash: see the follow-up commit line below; automated device run on SNN + NA5C + MIP11 2026-08-17 + user-verified dot handling on SNN)

**Goal:** the recognizer point + the ML Kit extension are trustworthy enough to be the capability
every later consumer point brokers, and the shared bind path is the one path.

**Questions to resolve at phase start** (one at a time):
1. Anything observed in M1 the user wants changed before freezing (wording, timing, dialog)?
   (rec.: no — freeze as built)
2. Confirm scope freeze: fixes only.

**Deliverables**
1. `/code-review high` over the arc's diff (`git diff <M0 base>...HEAD` — the **range**, not a bare
   ref; the base is the commit before M0's first commit — record it in M0's Outcome); fix confirmed
   findings.
2. **Boundary audit** rows added to `docs/extensions.md` and walked:
   - **14 — Outward payload of HandwritingRecognizer is bare geometry only.** `InkStroke` carries
     x/y arrays and nothing else (`InkPayload.fromStrokes` is the one reduction site); the other
     arguments are area/page size and ≤ 20 chars of pre-context; no other argument exists in the
     interface. Recorded as the explicit widening of row 3 — the first point that receives ink.
   - **15 — Ink is capped before it crosses and re-checked after.** `MAX_INK_STROKES` /
     `MAX_INK_POINTS` / non-empty equal-length arrays / positive sizes enforced host-side without
     binding (`InkCaps`), re-checked in the extension (`IllegalArgumentException`).
   - **16 — Inward payload is validated; nothing is stored.** Status outside `0..3` → `UNAVAILABLE`;
     text `?: ""` and truncated to `MAX_RECOGNIZED_CHARS`; shown only in a debug dialog; the core
     writes recognition results nowhere.
   - **17 — Failure changes nothing.** Not ready / timeout / too dense / absent extension → a toast
     or a missing sheet item; the page, the ink, and every other extension are unaffected; the model
     download is the extension's own state and survives the extension's disable/enable.
   - Re-walk rows **1, 6, 7** for the shared `ExtensionBinder` (signature re-check at bind, unbind in
     `finally`, every call has a timeout) — now one implementation for three clients.
3. `docs/extensions.md` final: "The capability pattern" (the proxy recipe above, verbatim);
   "Adding a capability point" rules appended after rule 11; "Writing an extension" gains a
   recognizer paragraph (bare geometry in, text out, status/prepare protocol, engine assets in your
   own sandbox, never log text); `README.md`; `CLAUDE.md` standing rules (recognizer facts, shared
   bind path, ML Kit dep is `:ext-mlkit`-only, model storage exception).
4. This file frozen; memory updated (arc complete).

**Tests:** full M1 device checklist again on all three devices + E1 items 1–3 + N1 items 2–3, 6, 10 +
v0 regression subset (create/open/write/flip, library create/rename/move/delete, cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

**Outcome (2026-08-17):**
- **Q&A:** Q1 freeze as built · Q2 fixes only **plus the arc-2 toast→dialog sweep** (user's choice).
- **Toast sweep:** one helper `Dialogs.problem(activity, title, message)` (`core/Dialogs.kt`; the M1
  helpers in `NewNotebookActivity` / `NotebookDebugMenu` now delegate to it). Converted: New-folder
  invalid/duplicate name, namer validation error / "didn't respond" / "Folder created — naming scheme
  not saved", scheme-dialog fetch failure + validate/unavailable, Rename invalid/duplicate name,
  `FolderPickerActivity` move collision + move-into-self (new strings `naming_problem_title`,
  `move_problem_title`, `move_into_self`). Left as toasts (confirmations / finishing screens):
  "copied", `RecoveryKeyActivity` tick-first, `NotebookActivity` open-failed (+ finish), the library
  debug store self-test result.
- **`/code-review high 4cdf74e...HEAD`** — 10 findings + cleanups. **Fixed (9):**
  1. *Download without consent* — M1's `onCreate` chain downloaded on the host's first `status()`
     bind, before the "Recognition model needed → Download?" dialog (Cancel / offline pre-check were
     cosmetic). → `ModelManager.warmUp()` (onCreate) builds the client only from the model-present
     flag; **`prepare()` is the only thing that starts the check→download→build chain**; `awaitReady`
     waits for a chain in flight (or the flag) and never starts one. AIDL comment updated. UX for a
     fresh device is unchanged (dialog at once, Download → progress → recognize).
  2. *`onEngineFailure` wiped the flag on any exception* (a slow first inference's `TimeoutException`
     made the model look "gone" → a re-download offer for a present model). → timeouts /
     interruptions are never engine failures; a real failure on the shortcut client **verifies** with
     one async `isModelDownloaded` and clears the flag only on `false`.
  3. *Process-lifetime `recognizeBusy` stuck* when the consent/offline dialog died with its activity
     (no `onCancel`) → every later Recognize tap silently returned. → the guard is a `WeakReference`
     to the owning activity (busy ⇔ owner alive).
  4. *A single failed line discarded the whole page* (the original tolerated per-line failures). →
     per-line tolerance; rethrow only if every line failed; a deadline overrun still aborts (never a
     silent partial).
  5. *Unrelated timeout budgets* (22 s ready wait + N × 10 s lines vs. the host's flat 30 s; the
     orphaned Binder thread ground on). → whole-call budgets `INK_BUDGET_MS` 9.5 s / `PAGE_BUDGET_MS`
     28 s taken at call entry; every ML Kit wait = `min(10 s, time left)`; out of time → ISE
     "recognition timed out" (generic host dialog; the warm retry is fast). `PageText.waitFor` tested.
  6. *Single-point strokes dropped* by the verbatim segmenter (g-paper draws pen taps; the original's
     views never produced them). → `PageText.widenDots` promotes them to two-point strokes before
     segmenting/ML Kit (tested, incl. through the segmenter).
  7. *`status()` = DOWNLOADING during a purely local cold check* (no flag, model present — an M0
     upgrade / wiped flag) → the host offered a download for a present model. → subsumed by fix 1: no
     flag → nothing starts → `NEEDS_DOWNLOAD` → consent → `prepare()` finds it present without
     downloading (the progress dialog counts the check).
  8. *Poll loop coerced every failed `status()` to DOWNLOADING for the full 5-min cap.* → 5
     consecutive failed polls → "Download failed".
  9. *`RecognizerNotReadyException` by substring-matching "not ready".* → the contract constant
     `ExtensionContract.RECOGNIZER_NOT_READY` (exact message; used by the service, compared by the
     host; documented for third-party recognizers).
  **Declined (1):** *hold one binding for the whole download poll loop* — contradicts M1's user
  decision (bind-per-poll, no binding held across the flow); the download demonstrably survives the
  unbind (M1) and the LRU-kill scenario is speculative. Recorded here, not in Deferred.
  **Cleanups taken:** dead `BIND_TIMEOUT_MS` aliases removed from the three clients; the debug-menu
  KDoc "UNAVAILABLE → toast" corrected; the `Slog`-unreachable-from-`:ext-*` exemption recorded in
  `CLAUDE.md`. **Not taken:** the ⋯ button block twinned from `DebugMenu` (debug-only, two lines of
  difference), `ExtensionRegistry` first-of duplication (3 lines each), the segmenter's dead filter /
  unused `Paragraph.bounds` (verbatim port — left verbatim). Refuted by the reviewer itself:
  namer revoke-vs-unbind ordering, "too dense" for a 0-size page, `TransactionTooLarge` at the cap,
  `Connectivity` VALIDATED strictness, manifest comment placement.
- **Boundary audit:** rows 14–17 written and walked against the code; rows 1/6/7 re-walked for the
  shared `ExtensionBinder` (note above the table). `docs/extensions.md` gains "Adding a capability
  point" (rules 12–17) + "The capability pattern" (the proxy recipe, verbatim + the Deferred shape) +
  the "Writing an extension" recognizer paragraph; `README.md`, `CLAUDE.md`, `docs/library.md`,
  `docs/notebook.md` updated. `:ext-mlkit` tests 15 → 18; all-module JVM green; `assembleDebug` +
  `:app:assembleRelease` build.
- **Installed:** SNN + NA5C + MIP11 (app + all three extensions; NA5C enable dance done — the *app*
  package was found disabled again at the start of the automated run and re-enabled).
- **Automated device run (2026-08-17, one Sonnet agent per device over adb + uiautomator dumps;
  no stylus — items 4/6/11 skipped by the user's decision):**
  - **SNN:** 1, 2, 3 (7 strokes · 4112 ms first bind), 5, 7 (offline OK), 8 (cold 4132 ms, no download
    dialog — flag path), 9, **13 (consent path: Cancel → zero `ModelManager` lines; Download → chain
    start → "model present (4723 ms)" → no `download start` → auto-recognize; flag restored)**, log
    hygiene (no text, binds = unbinds, no leaked connection), 10, 14 all PASS; 10b/12a–d SKIPPED —
    the Supernote IME is invisible to uiautomator and swallows every injected key (known trap).
  - **NA5C:** 1, 2, 3 (12 strokes · 1758 ms), 5 (89 strokes), 7 (55 ms offline), 8 (cold 1749 ms), 9,
    **13 (same shape: no chain on Cancel; "model present (6691 ms)", no download)**, log hygiene, 10
    (**both** `TemplateProviderClient` pairs — list on open + render on create — seen), 10b (prefill
    `Meeting 20260817 01`), **12a–d all PASS with the exact dialog strings** (12d via a controlled
    same-name pair, cleaned up), 14 all PASS.
  - **MIP11:** 1, 2, 9, 10, 10b, 12a–d, 14 PASS (typing works; exact strings); 3/5/7/8 **not
    exercisable** — the device has no ink and no stylus in the loop, so its first-ever consent + real
    ~20 MB download is the one item left for the user. Trap for the record: MIP11 ships
    `log.tag=I`, which silently drops every `Log.d` — the agent set `log.tag DEBUG` for the run and
    restored it.
  - **MIP11 retest** (after the user wrote a page there): **3 = the real first-ever-use path PASS** —
    "Recognition model needed" → Cancel → zero `ModelManager` activity → Download → progress counter
    (2 s … 40 s) → download done at 42–50 s on Wi-Fi → result dialog with no further tap (31 strokes ·
    193 ms); 7 (offline 125 ms), 8 (cold 1.7 s, no download dialog), 9, log hygiene PASS; 5 fell back
    to the blank-page check (only one written page on the device). MIP11 trap: `log.tag` reverts to
    `I` by itself mid-run — re-check `getprop log.tag` right before any log-dependent step.
  - No crash-buffer entries on any device; every device left in its starting state (Wi-Fi, packages,
    notebooks/folders, model flag).
- **Dot handling (M2 addendum, user-requested after the "Hi." tests):** ML Kit skips a pen-tap period
  or reads it as a comma (seen in the original app too — same x/y feed, same writing area, so it is
  the model, not the port). Extension-side only, pure + tested (`Dots`, 4 tests): (1) **`round`** —
  a tiny stroke (≤ 15 % of the line height both ways: periods, i-dots — commas, apostrophes, hyphens
  are taller/wider) is redrawn as a small 12-point circle at its centre before ML Kit sees it, instead
  of the 2–4 px lift-drag that reads as a comma tail or as zero-length ink; (2) **trailing period** —
  if a line's right-most stroke is a tiny dot in the baseline zone (below 45 % of the band; i-dots
  are high; slant allowance 15 % of the line height into the last letter), the text is made to end
  in `.` (`,` → `.`, missing → appended; `. ! ? : ; …` untouched). Applied in `recognizePage` per
  line (`recognizeInk` gets the rounding with the area height). Mid-line periods are not corrected
  (position is not unambiguous there). **Iterated on the user's SNN page (17 lines, 130 strokes)
  with a geometry-only debug log:** the tiny rule fixed 7 skipped periods; two *shaky* periods
  (3 × 15 / 3 × 10 px, comma-height, ML Kit → `,`) needed a second rule — small (≤ 15 % w, ≤ 30 % h;
  the user's real commas all exceed that), centre in the bottom 30 % of the band, no tiny stroke
  above (i-stem guard); baseline descent measured and rejected as a discriminator. Result: every
  written period → `.`, the three real-comma lines and the bare `Hi` untouched (user-confirmed
  intent). Installed on all three devices; documented in `docs/extensions.md`.

---

## Appendix A — Constants (this arc)

| Name | Value |
|---|---|
| `ACTION_HANDWRITING_RECOGNIZER` | `com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER` |
| `META_API_VERSION` / `API_VERSION` | unchanged (`…extension.API_VERSION` / `1`) |
| `MAX_INK_STROKES` / `MAX_INK_POINTS` | 2 000 / 60 000 |
| `MAX_PRECONTEXT_CHARS` / `MAX_RECOGNIZED_CHARS` | 20 / 20 000 |
| `RecognizerStatus` | `READY 0` · `NEEDS_DOWNLOAD 1` · `DOWNLOADING 2` · `UNAVAILABLE 3` |
| Timeouts | bind 3 000 ms · `status`/`prepare` 2 000 ms · `recognizeInk` 10 000 ms · `recognizePage` 30 000 ms |
| ML Kit | `com.google.mlkit:digital-ink-recognition:19.0.0`, model `en-US` (~20 MB, in the extension's sandbox) |
| Segmenter constants | `PARA_GAP_FRAC 0.9` · `BAND_COVERAGE_FRAC 0.15` · `FRAGMENT_MAX_STROKES 3` · `MERGE_OVERLAP_FRAC 0.4` |
| ML Kit extension package | `com.symmetricalpalmtree.notesprout.ext.mlkit` (debug `.dev`) |
| Debug action | notebook ⋯ → "Recognize page (ML Kit)" (debug builds only) |

## Appendix B — Allowed dependencies (in addition to arcs 1–2)

```
:extension-api   — unchanged (none)
:ext-mlkit       — project(":extension-api"), com.google.mlkit:digital-ink-recognition:19.0.0,
                   testImplementation junit:junit:4.13.2
:app             — unchanged
```
ML Kit transitively brings `play-services-base` / `play-services-tasks` / `mlkit-common` into
`:ext-mlkit` only. **Never** into `:app` or `:extension-api`. No `kotlin-parcelize`, no new plugins,
no serialization lib.

## Appendix C — Build & install (this arc)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug && ./gradlew testDebugUnitTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev       # BOOX: re-run after a few seconds, confirm `pm list packages -d`
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> shell am force-stop com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> logcat -s ExtensionRegistry RecognizerClient HandwritingRecognizerService ModelManager
```

## Appendix D — Reference map

| Concern | Where |
|---|---|
| Original ML Kit engine (what to port, what reaches the engine) | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/recognition/{MlKitHandwritingRecognizer,HandwritingRecognizer,StrokeSegmenter,PageTextRecognizer}.kt`, `docs/handwriting-recognition.md` |
| Client/registry pattern to extract from | `app/.../extension/{ExtensionRegistry,TemplateProviderClient,NamerClient,TemplateChoice}.kt` |
| Store binder gate (the proxy's future shape) | `app/.../data/extstore/{ExtensionStoreBinder,ExtensionStoreGate}.kt` |
| Notebook screen | `notebook/{NotebookActivity,NotebookToolbar,StrokeRows}.kt`, `res/layout/activity_notebook.xml` (`topBar`), `docs/notebook.md`; g-paper `getStrokes()` (any thread) `~/git/g-paper/docs/api.md` |
| Debug menu to twin | `app/src/debug/.../library/DebugMenu.kt` (+ release no-op), `core/ActionSheetDialog.kt`, `core/Dialogs.kt` |
| Extension reference implementations | `ext-naming/` (Gradle, manifest, `HostCallerCheck` use, Binder-safe exceptions), `ext-templates/` |
| Android / ML Kit references | `DigitalInkRecognition`, `DigitalInkRecognitionModelIdentifier.fromLanguageTag`, `RemoteModelManager.isModelDownloaded/download`, `Ink`, `WritingArea`, `RecognitionContext`; `Tasks.await`; AIDL `in List<Parcelable>`, `writeFloatArray` |
