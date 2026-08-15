# Drawing Engine Architecture

> Referenced from `CLAUDE.md`. Covers the three drawing engines, EPD/overlay rules, tool-state
> invariants, performance rules, and the committed-content render model.

## Files

- `notebook/NotebookView.kt` — interface for all engines; all drawing, lasso, heading, render ops
- `notebook/OnyxNotebookView.kt` — BOOX: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.
- `notebook/GenericNotebookView.kt` — standard Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `notebook/RattaNotebookView.kt` — Supernote (Ratta): live ink painted by the firmware's ink daemon, points from `MotionEvent`, deferred handoff. ⚠️ A **sibling copy** of `GenericNotebookView` — see the Ratta section below; shared-logic fixes must land in **both** files.
- `notebook/ratta/SupernoteInk.kt` + `notebook/ratta/RattaInkMap.kt` — the firmware binder client and the live-ink grey mapping (Ratta section below).
- `notebook/NotebookViewFactory.kt` — `createNotebookView()`: the single engine-selection point for all six drawing hosts (notebook, calendar, day-note, scratch pad, sticky editor, HWR enrollment). BOOX → Onyx decided first (no reflection); `isRattaDevice() && SupernoteInk.isAvailable()` → Ratta; else Generic. Logs the chosen engine per screen open at `Log.i`. `isRattaDevice()` (`core/Device.kt`) matches manufacturer `"supernote"` — **not** `"ratta"`, which appears nowhere in the build props.
- `NotebookActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`. One-finger deliberate swipe for page navigation (three guards: distance ≥50% screen width, velocity ≥1.5× fling threshold, horizontal dominance). Two-finger swipe left/right inserts a page after/before current and navigates to it (same guards). Two-finger swipe **down** opens the [Today dashboard](today-dashboard.md#the-two-finger-swipe-down) — a shared detector (`core/TwoFingerSwipeDown.kt`), not a per-screen port, and vertical-dominant where the insert swipe is horizontal-dominant, so the two can never both fire. Two-finger stationary double-tap = undo; three-finger stationary double-tap = redo. On BOOX the Onyx SDK intercepts 3-finger touches and sends `ACTION_CANCEL` before `ACTION_UP` — the 3-finger detector treats a cancel on an armed, stationary 3-finger gesture as tap completion. All of these detectors sit behind the pen-activity gate (see below) so a resting palm can't drive them.
- `MainActivity.kt` — notebook list, adaptive grid (3/2 cols at 480dp), pagination, empty state, bottom bar.

## Key Build Facts

- `minSdk = 29`; `android.enableJetifier=true` (Onyx SDK bundles old support classes)
- `jniLibs.pickFirsts` for `libc++_shared.so`
- `defaultConfig.ndk { abiFilters += "arm64-v8a" }` — all target devices are 64-bit ARM. Do NOT `exclude com.tencent:mmkv:1.0.19` — `onyxsdk-base` references it; removing it risks `NoClassDefFoundError`. ML Kit `libdigitalink.so` is 16 KB-aligned at `digital-ink-recognition:19.0.0`.
- `NotesproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout` — native SurfaceView occludes siblings below it
- `onSizeChanged()` calls `redrawCanvas()` (not just white fill) in both drawing views — handles the case where `loadStrokes()` runs before view layout

## EPD Rules — Never Violate These

**First-stroke fast-mode (`HWR_APP_SCOPE`):**
- The first stroke after opening a page / after a page-flip used to lag 1–2s on BOOX (G6/G102): the panel sits in a quality waveform and the first stroke pays a GC→handwriting mode-switch. Fix: `EpdController.applyAppScopeUpdate(HWR_APP_SCOPE, true, false, UpdateMode.HAND_WRITING_REPAINT_MODE, 0)` in `openRawDrawing` (pen branch only), pinning the app in the fast handwriting waveform so there's no switch. Proven the **sole** fix by an on-device sweep of every EPD mode (scribble / view-mode / system-fast all still lagged; only app-scope was instant, no ghosting).
- Applied once when the pen pipeline opens; stays active across page-flips; **cleared in `closeRawDrawingIfOwner`** (`clearAppScopeUpdate()`) so menus / dialogs / other screens render in normal quality. It follows pen ownership across sticky / scratch-pad handoff automatically. Keep it out of the handoff/lifecycle code — the minimal apply-on-open / clear-on-close placement is deliberate.

**Overlay lifetime:**
- The overlay ("writing mode") stays active indefinitely while the user writes. No idle-release timer.
- Legitimate handoff points: `setEraserMode(true)`, `eraseAll()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`, toolbar finger touch.
- `onPenLifted` is a DB-save trigger only — does NOT touch the overlay.

**Toolbar touch → overlay release:**
- Any finger `ACTION_DOWN` within `drawingToolbar.bottom` (intercepted in `NotebookActivity.dispatchTouchEvent`) calls `drawingView.releaseRender()` before the child button handles the event — **unless the pen-activity gate is closed** (see below); a palm landing over the bar mid-word is not a button press.
- `releaseRender()`: `setRawDrawingRenderEnabled(false)` → `invalidate()`. No `handwritingRepaint` needed.
- Overlay re-enables automatically via `onBeginRawDrawing` on the next pen stroke.
- Must use `dispatchTouchEvent` (not `setOnTouchListener`) — button children always consume touches, so `setOnTouchListener` on the ViewGroup never fires.

**Overlay handoff (`eraseAll()`):**
- `setRawDrawingRenderEnabled(false)` → white bitmap → `invalidate()` → `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` → re-enable
- **`handwritingRepaint` is required.** `setRawDrawingRenderEnabled` is a lightweight toggle; it does NOT clear the hardware buffer. Without it: gray residue + black flash.
- `EpdController.setUpdListSize(512)` in `openRawDrawing()` suppresses mid-session GC16 refresh — do not remove.
- `renderStroke` calls `invalidate()` on every stroke so the Android canvas stays continuously current with the overlay.

**Eraser overlay:**
- On eraser start: `setRawDrawingRenderEnabled(false)` + `invalidate()` — immediately, before any erase logic. If not released first, the overlay hides the bitmap erase result (phantom strokes remain visible).
- `handwritingRepaint` after erase gesture ends only — NEVER during move events (causes full EPD flash per stroke).
- `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.

**setTemplate() EPD handoff:**
- `setRawDrawingRenderEnabled(false)` → `redrawCanvas()` → `EpdController.handwritingRepaint()` → `setRawDrawingEnabled(true)`. Without `handwritingRepaint`, the template change is invisible on e-ink.

**Process-global pen ownership (cross-activity handoff):**
- The BOOX raw-drawing pipeline (`TouchHelper` → `EpdController` raw input) is a **single process-global hardware resource** — only one `OnyxNotebookView` can own it at a time, even though every drawing host (notebook, calendar, day-detail, scratch pad, sticky note) builds its own view + `TouchHelper`. All hosts are `standard`-launch Activities, so **two live drawing views coexist during any transition.**
- **The hazard:** Android runs the incoming screen's `openRawDrawing()` *before* the outgoing screen's `onDestroy → closeRawDrawing()`. The outgoing view's late close then tears down the pipeline the incoming view just claimed → the canvas silently stops accepting the stylus until a focus cycle re-arms it. Seen when switching calendar↔notebook, notebook↔notebook (recents/link), moving/copying pages, or closing a scratch-pad/sticky overlay. Intermittent because the incoming open is async (layout/focus-driven) while the outgoing close is synchronous in `onDestroy`.
- **Ownership guard (`OnyxNotebookView.penOwner`, companion `@Volatile`):** `openRawDrawing()` sets `penOwner = this`; both close sites (`onDetachedFromWindow`, `releaseResources`) route through `closeRawDrawingIfOwner()`, which calls `touchHelper.closeRawDrawing()` **only when `penOwner === this`** and otherwise skips (dropping local `isSetup` only). A superseded view can never close the live view's session. All access is main-thread — it is purely an ordering guard, no locking. This is the backbone; it makes every teardown ordering safe on its own.
- **Focus-independent reclaim (`resumeDrawing()`):** hosts call this from `onResume` (**not** `enableDrawing()`) so the surviving screen reclaims the pipeline without depending on the BOOX-flaky `onWindowFocusChanged(true)` event — reopen if released, restart if superseded (an overlay took it), else just re-enable. DayDetail also calls it when entering **Note** mode (`setViewMode`).
- **Clean handoff (`releaseForHandoff()`):** the opaque "open another drawing screen and finish" paths — calendar/day-detail → notebook (paste/send), notebook → notebook (recents-switch / link-follow), encrypt/decrypt reopen — call this immediately before `startActivity` so the outgoing view closes its session *while still the owner*, leaving no dangling raw-input session. The ownership guard remains the safety net for paths that don't (translucent overlays, backgrounding).
- **Diagnostics (`EPD_TIMING`):** `PEN_OWNER_CLAIMED`, `CLOSE_RAW_DRAWING_SKIPPED reason=notOwner` (guard catching a would-be clobber), `RESUME_DRAWING`, `RELEASE_FOR_HANDOFF`.

## Pen-Activity Gate — Palm vs. Finger Gestures

**The problem.** On Onyx, stylus ink runs through the SDK raw-drawing pipeline and never produces
Android `MotionEvent`s — but a palm resting on the glass still does. Every drawing host fed finger
events unconditionally to its gesture detectors, so a palm roll mid-word registered as a
tap / swipe / double-tap. The handlers that fired then reached into the **live pen session**
(`releaseRender()`, or `setLimitRect()` via the toolbar toggle), dropping the stroke being written.
One cause, two symptoms: strokes intermittently not registering, and false-positive double-taps.

**The gate.** `NotebookView.isPenActive` — true while the stylus is down, plus a tail of
`PEN_ACTIVE_TAIL_MS` (350 ms) after it lifts. The tail is deliberately longer than the platform
double-tap window (~300 ms) so the second half of a palm-induced "double tap" can't land just after
the pen leaves the glass and be treated as a clean gesture. Short enough that a deliberate finger tap
right after writing still registers — **this constant is the tuning dial** if taps ever feel
swallowed.

Tracked in both engines, from both directions:

| Engine | Source of truth |
|---|---|
| `OnyxNotebookView` | `onBeginRawDrawing` / `onEndRawDrawing` **and** `onBeginRawErasing` / `onEndRawErasing` (marked *before* the mode guards), **plus** stylus `MotionEvent`s in `onTouchEvent` — text-placement / shape-transform disable raw drawing, so the SDK callbacks never fire in those modes and the stylus arrives as an ordinary event. The lasso modes stay ON the raw path (hardware lasso trails — see below), so their pen activity comes from the raw callbacks; the MotionEvent half covers only their no-pipeline fallback |
| `GenericNotebookView` | stylus `MotionEvent`s in `onTouchEvent` — all ink arrives this way, so one hook covers every mode |
| `RattaNotebookView` | stylus `MotionEvent`s in `onTouchEvent`, same as Generic — the firmware paints the live stroke but returns no points, so every mode's ink still arrives as ordinary events |

**Applied on all five drawing screens.** Each checks `drawingView.isPenActive` at the top of its
finger branch in `dispatchTouchEvent` and, on the first suppressed event, latches
`fingerGesturesSuppressed` and calls its own `cancelFingerGestures()`.

| Screen | Gated detectors |
|---|---|
| `NotebookActivity` | page swipe, two-finger swipe down → Today, link/sticky follow, toolbar toggle, multi-finger undo/redo |
| `ScratchpadActivity` | page swipe, sticky tap, multi-finger undo/redo |
| `CalendarActivity` | nav swipe + day tap, two-finger swipe down → Today, sticky tap, multi-finger undo/redo |
| `DayDetailActivity` | sticky tap, multi-finger undo/redo (NOTE view only); two-finger swipe down → Today (**all four views** — it gates on the pen itself rather than riding the NOTE branch's gate, and consumes nothing) |
| `StickyNoteEditorActivity` | multi-finger undo/redo |

**Two rules when touching this code:**

- **`cancelFingerGestures()` must reset state fields directly — never route a synthetic
  `ACTION_CANCEL` through the detectors.** Every screen carries the Onyx 3-finger workaround, where a
  cancel on an armed, stationary 3-finger gesture counts as a *completed* tap. Routing through it
  would fire the exact false positive the gate exists to prevent.
- **Clear the first-tap timestamps** (`twoFingerTapFirstTime`, `threeFingerTapFirstTime`,
  `toggleFirstTapTime`), not just the in-flight flags. A stale armed first tap otherwise pairs with a
  real tap after the pen lifts and fires a phantom undo / redo / toolbar toggle.

On the four secondary screens the gate returns early out of the whole finger branch, so the
chrome/toolbar `releaseRender()` is skipped too. In `NotebookActivity` that release is entangled with
overflow-menu dismissal logic that must keep running, so there only the release call itself is
guarded. Same effect, different shape.

## Tool-State Invariants (OnyxNotebookView)

When a Dialog is shown over NotebookActivity, focus changes trigger `onWindowFocusChanged(false)` → `setRawDrawingEnabled(false)`. On return: `onWindowFocusChanged(true)` → `openRawDrawing()`. Also triggered by `onResume()` → `resumeDrawing()` (which reclaims the process-global pen pipeline — see the pen-ownership rule above).

| Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
|---|---|---|
| Pen | `true` | `true` (SDK manages) |
| Eraser | `true` | `false` (prevents phantom pen strokes on overlay) |
| Lasso / Lasso Eraser | `true` (hardware trail — raw-callback-driven) | `false` between gestures; enabled per-outline from `onBeginRawDrawing` |
| Text placement / shape transform | `false` | n/a |

Every raw re-enable site routes through `armRawForCurrentMode()`, which encodes this table:
no-op in text-placement / shape-transform, else raw on, plus render off for eraser and both
lasso modes. Failing the render-off half causes phantom pen strokes on the EPD overlay — they
look real but vanish on the next EPD refresh. `applyPenStyle()` is the single place the
firmware stroke style/width/ink are armed (pen = `STROKE_STYLE_PENCIL` + armed ink; lasso
trails below); `openRawDrawing()` calls it on both its open and restart paths, which is also
what re-asserts the ink across a restart.

### Hardware lasso trails (BOOX half of Supernote Phase 5)

The live lasso outline is painted by the **firmware**, not the Canvas: lasso mode arms
`TouchHelper.setStrokeStyle(STROKE_STYLE_DASH)` (black, 3px), lasso-eraser arms
`STROKE_STYLE_CHARCOAL` (grey, 6px — Onyx has no x-stream/hatched style like Ratta's
`LASSO_X`; charcoal is the textured style closest to the software chalk trail it replaced).
Both styles are device-proven on all five Tier-1 BOOX devices, need no restart, and survive
the handwriting fast-mode pin (`docs/onyx-pen-tools.md`).

How a gesture runs (all inside `OnyxNotebookView`, no host changes):

- **The raw path stays enabled in both lasso modes** and the whole gesture is driven from the
  raw callbacks (`beginRawLasso` / `beginRawLassoEraser` → move/list accumulation →
  `finishRawLasso` / `finishRawLassoEraser`). Stylus MotionEvents do not arrive while the raw
  path owns the pen; `handleLassoTouch` / `handleLassoEraserTouch` remain as the
  no-pipeline fallback (guarded by `rawLassoDriving()` against double-driving).
- **Overlay render is OFF between gestures** and enabled per-outline at the begin callback.
  That is what lets a drag-move start inside the selection box without painting a trail blip —
  no hover suppression needed (unlike Ratta's law 3; the Onyx begin callback decides
  drag-vs-outline before render is touched).
- **Drag-move rides the same shared helpers as the MotionEvent path**
  (`tryBeginLassoDrag` / `lassoDragMove` / `lassoDragFinish`): A2 fast mode, snap, backing
  bitmap — all unchanged, just fed from raw points.
- **Trail wipe at pen-up** = the proven smart-lasso wipe: `setRawDrawingRenderEnabled(false)`
  + `invalidate()` + posted `handwritingRepaint` (`wipeRawLassoTrail`). The selection lasso
  wipes before dispatching `onLassoComplete`; the eraser lasso's real gestures let
  `performLassoErase`'s completion repaint do it.
- Gesture geometry: per-point move stream is the fallback; the batched
  `onRawDrawingTouchPointListReceived` list (which can arrive in multiple batches per
  contact) is authoritative.
- A barrel-button / eraser-end press mid-gesture cancels the capture
  (`cancelRawLassoGesture` from `onBeginRawErasing`) and the SDK's hardware erase proceeds.

## Stylus Barrel Button

The BOOX stylus barrel button is reported to Android as `TOOL_TYPE_ERASER` (not `BUTTON_STYLUS_PRIMARY`). In every mode that keeps the raw path enabled (pen, eraser, and — since the hardware lasso trails — both lasso modes) the Onyx SDK intercepts this at the hardware level and fires `onBeginRawErasing` → the existing erasing callbacks handle it (in a lasso mode this first cancels any in-flight trail capture). In modes where `setRawDrawingEnabled(false)` is active (text placement, shape transform, or a lasso mode running on its no-pipeline fallback), the SDK is silent and the button event arrives only via Android's `onTouchEvent`. Both views intercept `TOOL_TYPE_ERASER` (and `BUTTON_STYLUS_PRIMARY` for completeness) **before** the per-mode handler in `onTouchEvent`, routing to `handleBarrelButtonErase` which calls `eraseAtPath` → `finalizeEraseRedraw` → `handwritingRepaint`. This gives consistent erase-on-button behavior regardless of active toolbar tool.

## Ratta (Supernote) Firmware Ink Engine

Supernote devices (Nomad + Manta) run `RattaNotebookView`: live strokes are painted by the
firmware's ink daemon — the Ratta analogue of the Onyx SDK overlay — while everything else
(erase hit-testing, lasso, gestures, text, shapes, snapshots, the committed `RenderNode`) is the
Generic engine's logic. Everything below is hardware-measured on both devices (2026-08, firmware
`Chauvet.E103…2389`; the ink path survived a firmware update mid-test). The permanent calibration
tool is `SupernoteProbeActivity` (debug build) — pen-code sweep, REG registration lab, COL grey
cycler, barrel lab, delayed clear-matrix — with its measured findings recorded in the
`app/src/debug/AndroidManifest.xml` comment, like `PenToolSpikeActivity` on BOOX.

**⚠️ Sibling copy — the one structural rule.** `RattaNotebookView` is a **copy** of
`GenericNotebookView` with the live-ink parts edited, not a subclass (deliberate: zero risk to the
shipping Generic devices). **Any fix to shared logic — lasso, erase, gestures, rendering — must be
applied to both files.** The collapse into a shared `CanvasNotebookView` base is filed in
`BACKLOG.md`.

**No fallback, no kill switch** (locked decision). Engine choice happens once, at construction. A
firmware failure afterwards logs `Log.w` (survives release — never `Slog.d` here) and toasts once
per view instance, then keeps running. Failures must be loud, never silently papered over.

**True full-screen chrome.** Supernote has no pull-down status bar, so the app's top guard band is
0 on Ratta (`TopGuard.heightPx()` gates on `isRattaDevice()`): the notebook toolbar and every
immersive screen's chrome sit flush at the top edge. Nothing engine-side depends on the guard — the
pen-exclusion band derives from the toolbar's laid-out rect and tracks it automatically. See
`docs/design-system.md` (top guard band).

### How the firmware ink path works

`SupernoteInk` is a thin binder client for the firmware ink daemon (`service_myservice`, raw
`Parcel`/`transact`): claim the pen for `"notesprout"`, configure the pen (type code, EMR size,
colour code), send disable-area rects, clear the overlay. `enableFullUiAuto(true)` (reflected off
the hidden `eink` service) is required for a third-party app to get ink at all;
`enableAutoRegal(true)` at setup is what keeps handoffs ghost-free. **Never** call
`screenRefresh`/`sendOneFullFrame` per stroke or handoff — both flash (`sendOneFullFrame` is
acceptable inside `eraseAll`, where a flash is expected).

The critical architectural facts:

- The daemon paints stroke pixels to the EPDC overlay at sub-frame latency, in **screen
  coordinates**, composited **above the framebuffer**. Pixels under overlay ink are frozen against
  app updates, and no app repaint can remove overlay ink. `screencap` cannot see the overlay
  (chrome yes, ink no) — ink appearance needs eyes/photos on the device.
- The firmware returns **no point data**. Everything persisted comes from `MotionEvent` in
  `onTouchEvent`, exactly like Generic — which is why the pen-activity gate and all gestures work
  unchanged.
- **`DISABLE_AREA` is also the "firmware off" switch.** There is no disable transaction;
  `fullScreenDisable()` (a rect covering the whole panel) is how you stop the firmware painting.
  One transaction accepts at least five rects.
- Measured constants: production pen = **NEEDLE (10)** (uniform width, matches the baked
  polyline); lasso trail = **LASSO_DASH (4)**, lasso-eraser trail = **LASSO_X (3)**, both at EMR
  300 with a BLACK payload (eraser semantics come from the colour-255 payload, not the type code);
  pen EMR = `(widthPx * 100).coerceIn(200, 1200)` — an EMR near 3 paints an invisible sub-pixel
  line and reads exactly like a dead firmware path; eraser EMR =
  `(radius * 50).coerceAtLeast(400)`. Pen code 12 is broken firmware-side (random giant laggy
  blob) — never use it; codes 17–31 alias to INK (16).

### The deferred handoff (never bake per pen-lift)

Do **not** bake + `clearAll()` on every pen-lift — that fights the hardware and produces a flash
plus a ghost/enlargement. Finished strokes enter the `strokes` model on pen-up (so saves,
snapshots, hit-tests and the smart-lasso/scribble gates are correct immediately), but
`commitActiveStroke()` **skips `redrawCanvas()`**, setting `pendingBake` instead. The visual bake
and the overlay clear wait for a natural boundary — `releaseFirmwareOverlay()` = bake
(`redrawCanvas()`, which re-records the node from the whole `strokes` list) then `clearAll()`.
`redrawCanvas()` itself carries the handoff guard, so any redraw triggered outside the release
path (throttled erase redraws, drag commits) can never leave the same ink shown by both layers.
Same discipline `OnyxNotebookView` follows.

**Boundaries** (each releases the overlay): tool change (release **first**, then
`applyToolToFirmware()` — the shared helper is `firmwareToolBoundary()`); `releaseRender()` (every
toolbar touch); `releaseForHandoff()` (+ `enableFullUiAuto(false)`); `resetOverlay()`; focus loss
(+ full-screen disable); snapshot capture (release first so the panel matches); page nav /
`clearForPageLoad()` / `loadStrokesWithBitmap()` / `setTemplate()` (release **before** the content
swap); `eraseAll()`; and every erase contact's `ACTION_DOWN` (so software erase works on a
fully-baked page).

### ⭐ The three overlay laws

Every change to this engine must obey these — each was measured the hard way:

1. **A clear needs a co-presented app frame.** `clearAll()` alone reconciles nothing, ever — the
   panel drops overlay ink only when an app frame is presented in the same breath. Always pair
   `clearAll()` + `invalidate()`.
2. **Clears near pen-lift AND at fresh pen-down can be eaten** by the daemon's
   stroke-finalization window, whose length varies by device and moment. The remedy is
   `releaseGestureTrace()`'s **retry ladder** — idempotent `clearAll`+`invalidate` pairs at
   450 ms → 1 s → 1.9 s, plus an immediate flush at the next EMR pen-down — armed after every
   gesture-consumed stroke (smart lasso, scribble, lasso trails) and every erase contact. The
   worst-case ~2 s trace lag is a measured firmware constraint the user accepted — do not chase
   further latency here.
3. **The firmware latches pen state at contact start.** A suppress/disable issued at
   `ACTION_DOWN` is too late — it must be issued from the **hover stream**, before the tip lands.
   This is why the barrel suppress, the eraser-end suppress, and the lasso drag-move suppress all
   key off hover (`updateBarrelSuppress`, `updateLassoDragHoverSuppress`); the `ACTION_DOWN`
   disable is kept only as a no-hover backstop.

### Per-tool firmware state

| Mode | Firmware action |
|---|---|
| Pen | `claimPen()` + `setPen(NEEDLE, emrSize(w), RattaInkMap.firmwareColorFor(hex))` |
| Eraser tool | `setEraser(false, eraserEmr())` — stops firmware ink along the path; the software hit-test does the removal |
| Lasso / lasso-eraser | `setPen(LASSO_DASH / LASSO_X, 300, BLACK)` — live hardware trail; lift-wipe = the clear ladder |
| Text placement / shape transform | `fullScreenDisable()` (Canvas-drawn overlays; no firmware ink) |
| Drag-move inside lasso | `fullScreenDisable()` from **hover** over the selection box (law 3); re-arm on hover-out; `setLassoMode(false)` cancels a live drag *before* the tool boundary |
| Barrel button held / eraser end in hover range | `fullScreenDisable()` from hover (below) |
| Leaving a suppressed mode | `applyToolToFirmware()` restores disable areas + the armed tool |

### Disable areas — screen space, complement bands

The firmware knows nothing of view bounds or the window stack, so all geometry is converted
view → screen via `getLocationOnScreen`:

- `applyDisableAreas()` always sends **complement bands** — up to four rects covering everything
  outside the view's screen rect — plus the host's chrome exclusion (`setToolbarExclusion`,
  applied live; hosts push updated rects for the overflow menu / shape toolbar / colour panel).
  The bands are what keep ink off the calendar/day-window toolbars and off the notebook visible
  around the translucent scratch-pad/sticky windows. On the full-bleed notebook host all four
  bands are empty.
- `fullScreenDisable()` uses the **real panel size** (`Display.getRealSize`, cached at
  attach/`onSizeChanged`) — view dims miss the panel's bottom strip on inset hosts, and
  detach-time teardowns can no longer reach the display.
- **Model-side exclusion filter:** chrome gaps leak `MotionEvent`s (e.g. the overflow menu's blank
  areas), so `appendStrokePoints()` drops pen points inside the exclusion rect and splits strokes
  at the boundary — the model always matches what the firmware painted. A gesture that never left
  the zone commits nothing (`gestureHadInk` gates `checkAndDispatchGesture`).

### Barrel button & eraser end

The OS delivers the side button to apps **from hover** (`BUTTON_STYLUS_PRIMARY`; the OS preference
is `Settings.System` `end_button_behavior`, app-readable, `=2` delivers). The firmware natively
reacts to a held button by painting its lasso-x trail **ignoring the app's pen config but
respecting disable areas** — so `updateBarrelSuppress` full-screen-disables from hover while held
and `applyToolToFirmware()` restores on release. The erase decision is **sticky per contact**
(`strokeSawBarrel`): the button is released a beat before pen-lift, and per-event recomputation
committed phantom strokes. The **physical eraser end** rides the same hover suppress
(`TOOL_TYPE_ERASER` in hover range) — the firmware's native pixel-wipe only touches its own
overlay pixels and flashes across baked strokes, so suppressing it and letting the software
stroke-erase repaint is strictly better. Every erase contact arms the clear ladder on lift.

### Lifecycle — process-global `inkOwner`

Firmware ink state (the pen claim, full-UI ink, disable areas) is process-global, exactly like the
Onyx pipeline, and Android runs the incoming screen's `onResume` before the outgoing screen's
detach. The static `inkOwner` guard (mirror of `OnyxNotebookView.penOwner`) gates every
process-global teardown — `teardownFirmwareInk`, detach, `releaseForHandoff`, `releaseResources`,
**and the clear ladder** (a ladder armed by a lasso lift must not fire into a successor's
session). Setup re-asserts from attach **post-layout** (deferred to `onSizeChanged` when 0×0 — an
empty disable rect and garbage `getLocationOnScreen` otherwise), focus gain, and the
`resumeDrawing()` override (host `onResume` — the focus-independent reclaim, and what flips
`inkOwner` back after a translucent overlay host). `enableDrawing()` = `applyToolToFirmware()`;
`disableDrawing()` = **bake first**, then full-screen disable (same-window view switches, like the
day window's Note→Events, cross no focus boundary). `releaseResources()` ends in a full-screen
disable, not `clearDisableAreas()` — device-confirmed harmless system-wide (the daemon resets
per-claim state; the system's own notes app inks fine after leaving).

**Pen-approach re-arm (the daemon can drop an arming).** An arming issued from attach/focus-gain
can land mid-window-transition and be silently ignored by the daemon: measured on the
create-notebook→immediately-open path (Manta, 2026-08-11), a logcat-complete, byte-identical
correct arming sequence (claim → full-UI ink → areas → pen) still produced a fully dead session —
no live overlay ink, no canvas bake (the deferred-handoff design shows *nothing* when the overlay
is dead), strokes still captured and saved — until the next natural re-arm (reopen). Intermittent;
same family as the eaten clears (law 2). Remedy in the same spirit as the clear ladder:
`setupFirmwareInk` arms a one-shot, and the **first stylus approach** afterwards re-asserts the
whole session (`rearmOnPenApproach()` — claim + full-UI ink + tool/area push) from the hover
stream, law 3's guaranteed pre-contact channel; a pen-down backstop covers a no-hover contact
(too late for that stroke's paint, heals the rest). It runs *before* the barrel/lasso hover
suppressors on the same event, so a transient full-screen disable it clears is immediately
re-applied by their re-evaluation. Cost: four binder transactions once per screen entry.

### Live-vs-baked appearance

- **Registration.** The `MotionEvent` stream lands slightly **left** of the physical tip; the live
  firmware ink is true. `compensateRegistration()` (one chokepoint — `offsetLocation` on
  stylus/eraser tools at all three input entries) shifts **+2 px on the Nomad / +3 px on the
  Manta**, branched on min screen dimension ≥ 1600 because the Manta reports `Build.MODEL` as
  `Supernote Nomad`. This corrects persisted data toward physical truth. Caveat: the constants are
  believed model-level (the offset is a software disagreement between two readings of one
  digitizer, ≈0.15% of panel width on both) but were measured on one unit per model — the
  user-facing calibration screen is filed in `BACKLOG.md`.
- **Colour.** The firmware accepts four colour codes (BLACK / DARK_GRAY / GRAY / LIGHT_GRAY), and
  they render **far lighter than named** (DARK_GRAY ≈ `#AA`, GRAY ≈ `#CC`, LIGHT_GRAY ≈ `#F0`,
  near-invisible). `RattaInkMap.firmwareColorFor(hex)` maps the armed ink's Rec. 601 luma with
  thresholds **85 / 187 / 222**, calibrated to those measured render tones; `setPenColor` re-arms
  the firmware when the plain pen is active. The 16-grey ladder therefore gets 3 visible live
  shades — **accepted as best-possible; do not revisit the thresholds** (shifting them only
  misaligns live from baked to fake variety the panel cannot render). Baked strokes keep their
  true stored hex — a notebook written on a Supernote opens in full colour elsewhere.

### Device traps

- **The Manta identifies itself as a Nomad** — every `ro.product.*` property is byte-identical.
  Branch on screen size or not at all; over adb, the serial is the only discriminator.
- **Finger taps are suppressed system-wide while the stylus is in EMR range** (the Supernote twin
  of the G102 resting-contact trap) — check for a hovering pen before blaming a gesture detector.

## Performance Rules (Do Not Regress)

**Save path:** Wrap INSERT OR IGNORE loops in `db.withTransaction {}`; track `persistedStrokeIds` set and skip `toJson()` for already-persisted strokes.

**Load path:** deserialize objects + strokes off the main thread, then hand the in-memory lists to the view on the main thread (`loadStrokesWithBitmap(strokes, null, template)`) which records the committed `RenderNode` (see the render model below). No bitmap is rasterized at load. Stroke JSON parse is the dominant cost, so it is prefetched and cached (see "Neighbor prefetch cache").

**Erase path:** `LiveStroke.boundingBox: RectF` pre-computed at creation; `eraseAtPath` builds an AABB and rejects non-intersecting strokes in O(4 floats). `throttledEraseRedraw()` redraws at most once per 60ms; `finalizeEraseRedraw()` forces one clean redraw on gesture end before `handwritingRepaint`.

---

## Committed-Content Render Model (RenderNode)

Committed page content is drawn through a hardware `RenderNode` — the native equivalent of the
Flutter port's retained Skia layer (API 29+; our `minSdk` is 29). There is **no** on-screen render
bitmap and **no** on-load snapshot fast-path. Page load is: read objects + strokes from DB → record
the node → draw.

**The node (`committedNode = RenderNode("committed")`, both engines):**
- `redrawCanvas()` records it: `committedNode.setPosition(0,0,w,h)` → `beginRecording(w,h)` →
  `drawCommittedContent(rc)` → `endRecording()` → `invalidate()`. Sized in `onSizeChanged`.
  (`GenericNotebookView` calls the recording from `commitActiveStroke` / its own `redrawCanvas`.)
- `drawCommittedContent(canvas)` is the extracted draw routine: white → template → headings → text →
  lines → shapes → links → sticky → strokes. It is used **both** to record the node and as the
  software fallback below.
- `onDraw` branches by canvas type:
  `if (canvas.isHardwareAccelerated && committedNode.hasDisplayList()) canvas.drawRenderNode(committedNode) else drawCommittedContent(canvas)`.
  A `RenderNode` can only be drawn on a hardware canvas; the software branch keeps the Onyx
  `handwritingRepaint` panel-capture path correct (it re-draws the view through a software canvas).
- The node is re-recorded only when committed content actually changes (stroke commit, erase finalize,
  template change, load, undo/redo). During active writing only `invalidate()` fires — live ink is the
  Onyx overlay, and `onDraw` just re-blits the cached node (a GPU texture blit, no per-stroke path
  re-tessellation). `releaseResources()` calls `committedNode.discardDisplayList()`.

**Page-turn hold:** `clearForPageLoad()` (override) clears the in-memory lists and disables render but
does **not** re-record the node or refresh — the outgoing page stays visible until the incoming page's
`loadStrokesWithBitmap(...)` records the new node, so there is no blank flash between pages. Nav sites
call `clearForPageLoad()` instead of `eraseAll()`.

### Library-grid cover (index only — no per-page snapshot)

There is **no** per-page `data.snapshot` field anymore. The only snapshot is the **notebook's
library-grid cover**, stored in the global index (`NotebookObject.snapshot` in `notesprout.db`), not
in the `.soil`. `captureSnapshot()` (both engines) builds a self-contained bitmap of the current page
independent of any render layer; it is called **once, at notebook close** and written to the index via
`sealNotebook → cacheSnapshotIfAllowed(nbId, snapshot)` (unencrypted notebooks only — encrypted ones
never cache page content in the plaintext index).

**When captured:** close/back only (synchronously in `closeNotebook()` on the main thread, before
`sealNotebook()` dispatches to IO). `captureSnapshot()` returns `null` if content is empty or the view
isn't laid out (w=0/h=0). **NOT** on navigation, `eraseAll()`, or page delete.
**Critical:** `onWindowFocusChanged(false)` fires AFTER `finish()` (`soilDatabase` already null), so
`closeNotebook()` must capture the cover itself — never rely on a focus-loss hook.

**Page-index / link-picker thumbnails** are rendered **on demand** from page content
(`NotebookExporter.renderPageThumbnail`), not from any stored snapshot. Three things keep a render
fast: (1) each thumbnail rasterizes **directly at card scale** via `renderPageBitmap`'s
`renderScale` (the canvas + template decode are scaled down, so a heavy page draws ~scale² fewer
pixels than a full render + downscale); (2) a grid page renders across a **bounded worker pool** (up
to `availableProcessors()`, capped at 4) sharing one Room/WAL connection, each worker posting its
bitmap to Main as it finishes so cards fill in progressively; (3) thumbnails use the **lean stroke
parse** (`LiveStroke.fromPointsJson`, points-only) since the thumbnail renderer draws plain
fixed-width black paths and never reads pressure/tilt/color/width.

Both grids additionally keep the whole visit fast (the Supernote perf sweep): the Room connection
is opened **once per visit** through `KeyOpener.roomFactoryFor` (raw-key open when the derived key
is cached — skips SQLCipher's ~300–700 ms KDF, which used to be paid per render pass) — the link
picker, which renders pages of more than one notebook, swaps the held connection when the browsed
path changes. `loadPageRefs` takes an optional cached raw key (`KeyOpener.cachedRawKey`) for the
same reason, falling back to the passphrase open if the cached key is stale. Decoded **template
bitmaps are shared** across renders via an optional `templateCache` on `renderPageThumbnail` (pages
usually share one template — decode once per size, not once per card; template row ids are UUIDs,
so the picker's cross-notebook entries coexist). Rendered thumbnails live in a per-visit **LRU
keyed by page id** (~3 grid pages: visible + both neighbours, which are **prefetched** after the
visible cards so pagination lands warm). Entries survive reloads, tab switches, and grid flips
because nothing on either screen changes a surviving page's pixels — the page index's **set
template** is the one exception and invalidates exactly the affected ids (a page created from the
picker is a new id → natural miss); the caches clear wholesale only on a card-size change.
Evicted/cleared bitmaps are dropped to GC, never `recycle()`d — an evicted entry may still be on a
visible ImageView. The picker's current tab also falls back to the launch-restore-resolved key
(`KeySession` first, then `resolvedCurrentKey`), so a cold session no longer leaves its cards blank.

**Cleanup:** `NotebookCompactor` (runs on every seal, incl. encrypted, and the manual sweep) strips
any legacy `data.snapshot` from page rows and hard-deletes legacy `type='cover'` rows.

### Neighbor prefetch cache (`NotebookActivity`)

Stroke JSON parse (~260–480ms on heavy pages) is the dominant load cost, so it is cached and
prefetched:
- `strokeCache` — LRU `LinkedHashMap` (access-order, bounded to `MAX_CACHED_PAGES = 6`), keyed by
  `pageId`, guarded by `strokeCacheLock`. Each entry is `CachedStrokes(strokes, version)`.
- **Version = `NotebookDao.getMaxContentUpdatedAt(layerId)`** — `SELECT MAX(updatedAt)` with **no**
  `deletedAt IS NULL` filter, so soft-deleted (erased) strokes bump it. A ~10ms query, so the cache
  is self-invalidating: any edit changes the version and the stale entry is discarded.
- `deserializeStrokesFromDb(db)` — cache lookup validated inline against the current version; a hit
  skips the parse entirely.
- `parseStrokesForLayer(db, layerId)` — parses in parallel chunks across `Dispatchers.Default` when
  the row count ≥ `PARALLEL_PARSE_THRESHOLD = 300`. `parseStrokeRow` takes a lean JSON path
  (`LiveStroke.fromPointsJson`) when the row has no `pressure`/`tilt` fields.
- `prefetchNeighbors()` — after a page displays, parses N-1 / N+1 into `strokeCache` (stale-while-
  revalidate) so the next turn is a cache hit.

Navigation captures **no** snapshot — the library-grid cover is captured only at notebook close (see
above), so page turns do zero PNG-encode work.

---

## Undo/Redo System

- Session-scoped (not persisted across process death)
- `history/UndoRedoAction.kt` — sealed class: stroke add/erase, page add/delete/clear/copy/paste/move, lasso erase/cut/delete/paste/move, heading create/remove/text-edit, text insert/edit/remove/convert, **scribble erase**
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**Cross-page actions:** Never call `saveAndSwitchPage()` — it calls `eraseAll()` which wipes in-memory strokes. Use the two-phase approach: capture the leaving page's cover snapshot inline (dirty-gated) → navigate → load from DB → apply the action → re-record the committed node.

**Same-page stroke path:** Never calls `eraseAll()`. Updates the in-memory stroke list directly and re-records the committed node via `loadStrokesWithBitmap(strokes, null, currentTemplateBitmap)` (`NotebookActivity` field set in `displayPage()`). Keep `persistedStrokeIds` in sync.

---

## Template System

Templates live in **two layers**:

1. **The library** — reusable templates organized in nestable folders, stored as first-class objects
   in the **global index** (`notesprout.db`). This is the user's permanent collection, independent of
   any notebook. See the Templates subsection of [`data-architecture.md`](data-architecture.md) for
   the object model (`template` / `template_folder` types, `TemplateObject` payload, base64-in-`data`).
2. **Applied templates** — when a library template is used, it is **copied into the notebook's `.soil`**
   as a `type = "template"` row. A page references its active template by UUID via `data.template`.
   `parseTemplateId(data)` reads `data.template` from a page row (empty = Blank). This layer is
   unchanged from before the global-index migration; deleting a library template never affects
   notebooks that already used it.

`.soil` template rows keep `data` JSON `{ "width", "height", "name", "image" (base64) }` (`TemplateData`).
The library `TemplateObject` differs: the **name lives in `ObjectEntity.name`**, not the JSON.

**Render rule — the template paints into the page's stored rect, not the view.** Page content is
absolute page coordinates anchored top-left, so the template must stretch to the page's
`boundingBox` size (the screen of the device that created the page) or a backup restored onto a
different-size screen skews the template against the ink. `NotebookView.setTemplatePageSize(w, h)`
carries that rect — sticky per page, set by `NotebookActivity.displayPage()` from `PageLoadResult`;
`0×0` falls back to stretch-to-view (legacy behavior, and the standing behavior for hosts that never
call it: calendar / day-note canvases). All three engine views apply it at their **three** template
draw sites — `drawCommittedContent`, `buildRenderBitmap`, `captureSnapshot` (cover) — via a shared
`templateDestRect()` helper (sibling-copy rule: keep all three views in step). The export /
thumbnail renderer (`NotebookExporter.renderPage`) already drew the template into the page rect, so
screen, thumbnails, covers, and exports now agree. Templates themselves are **stretch-to-page by
design** (imported PNGs can be any size); the page rect, not the image's pixel size, is the
authority. New pages take the *current* device's screen size (`addPage`/`addPageBefore` →
`screenBounds()`), so a travelled notebook can hold mixed page sizes — each page stays
self-consistent.

**TemplateBrowserActivity** — one full-screen Activity (`Theme.Notesprout`, no immersive mode) drives
all contexts via `EXTRA_MODE`:
- `MODE_MANAGE` — launched from the MainActivity toolbar (`btnTemplates`). Full management: browse,
  import PNGs, new folder, long-press action sheet (rename / copy / move / delete / pin-unpin /
  **export**, with self-descendant + conflict guards), search, sort. No selection result.
- `MODE_PICK` — selection. Returns `RESULT_TEMPLATE_ID` (`""` = Blank, else a library `TemplateObject`
  id). With `EXTRA_COLLECT_NAME=true` it also shows a name field + CREATE button (the New Notebook
  flow) and returns `RESULT_NOTEBOOK_NAME`. Long-press management is omitted in PICK.
- `MODE_SAVE_TARGET` — cross-launched from the page-index "Save as Template" export option. Behaves
  like the move/copy destination picker (folders only, breadcrumb nav, no template cards) with a
  "Choose a folder" confirm bar (**Save Here** + **Cancel**, reuses `pickerToolbar`). Save Here prompts
  for a name (default = `EXTRA_SAVE_DEFAULT_NAME`, dup-checked with ` (2)`… like Import), reads the PNG
  at `EXTRA_SAVE_SOURCE_PATH`, and calls `repository.createTemplate` itself; returns only `RESULT_OK` /
  cancel. New-Folder stays available; everything else is hidden. (See `mainactivity-and-recents.md`
  for the host export sheet. Creation ≠ use → no recents recording.)

**Top toolbar (S11):** Search / Sort / Pinned / Recents live in a right-aligned cluster in the
`breadcrumbBar` (mirrors MainActivity). New-Folder + Import stay in the bottom bar with pagination.
`btnRecents` is GONE in MANAGE (selector-only), visible in PICK; `btnPinned` is visible in both.

**Pinned & Recents views** — alternate flat, paginated card grids (templates only, no folders, no
breadcrumb nav) toggled by the top-bar buttons, reusing the existing grid + pagination:
- **Pinned** (`enterPinnedView`/`exitPinnedView`, `isPinnedView`) — `repository.getPinnedTemplates()`.
  Available in **MANAGE and PICK**. Long-press management (incl. Pin/Unpin) applies in MANAGE only.
- **Recents** (`enterRecentsView`/`exitRecentsView`, `isRecentsView`) — `TemplateRecentsManager.resolve`,
  newest-first. **PICK-only.**
- Pinned / Recents / Search are mutually exclusive (entering one exits the others). Tap behavior matches
  the root browse grid for the mode: `collectName` → set pending + mark + stay (CREATE confirms); plain
  PICK → select + `finish()`. The **Blank** card belongs to root browse only (never pinned/recents).
- `applyOverlayViewUI` is the single authority handling both overlay toolbars + the action-button
  cluster. See `data-architecture.md` for the pinned-templates list + recents store.

**Export Template (S13, MANAGE):** the template long-press menu has an **"Export Template"** action
(after Rename, before Pin) → `showExportTemplateChoice` → an "Export template" `AlertDialog` with
**Save to device** (`CreateDocument` launcher) and **Share** (`ACTION_SEND` chooser). `writeTemplatePng`
decodes `TemplateObject.image` and writes the **raw bytes** (no re-encode) to
`cacheDir/exported_pngs/<name>.png`; reuses the `${applicationId}.fileprovider` authority. Single action
opening a chooser (the app-wide export pattern) — not two menu items.

Thumbnails decode-sampled from the full base64 on `Dispatchers.IO`, cached in-memory keyed by
`"${id}:${updatedAt}"`. Adaptive grid — 4 columns ≥1500px, else 2. Template cells use `shape_bordered`
+ 1dp inset (never `clipToOutline` — it clips the border at rounded corners).

**In-notebook `TemplateDialog`** (slim, single-view — owned by `NotebookActivity`, which holds the live
`.soil` connection): **Blank** + a quick-pick grid of templates **already used in this notebook**
(`db.notebookDao().getTemplatesSorted()`) + a **"Browse Templates…"** button that launches the browser
in `MODE_PICK`. On a library pick, `NotebookActivity.insertLibraryTemplateIntoSoil(id, db)` copies the
`TemplateObject` into the open `.soil` as a new `type="template"` row, then
`applyTemplateToCurrentPage(soilRowId, bitmap)`. The browser Activity **never opens a `.soil`** —
avoids cross-Activity WAL/sidecar risk.

**Template inheritance on new page:** `addPage()` reads the current page **fresh from DB** via
`dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not
refreshed after `applyTemplateToCurrentPage()` writes to DB.
