# Plan — Hardware dashed line for the lasso selector

> **Status:** planned, not started. Written 2026-08-03 after two failed attempts (both reverted).
> **Goal:** the in-progress lasso outline is drawn by the BOOX firmware's own dashed stroke style
> instead of an Android `DashPathEffect`, for genuinely real-time on-screen feedback.
>
> Background on the SDK capabilities this depends on: [`docs/onyx-pen-tools.md`](docs/onyx-pen-tools.md).

---

## 1. Scope

**In scope — the selection lasso's in-progress trail, on BOOX only.**

**Explicitly out of scope:**

| | Why |
|---|---|
| The established selection box | Works and looks right; stays an Android `drawRect` with `lassoPaint` |
| `GenericNotebookView` (Wacom / Supernote / tablets) | No firmware dash exists there. Keeps its `DashPathEffect`. **The `NotebookView` interface must not change**, or this leaks into a second engine |
| Lasso **eraser** mode (`handleLassoEraserTouch`) | Separate chalk-grain trail, deliberately different. May adopt this later; not now |
| Shape-transform / text-placement modes | Share the "raw drawing off" pattern but are not part of this goal |

---

## 2. What we already know (verified on-device)

From the pen-tool survey — do not re-derive:

- **Style 5 (`DASH`) renders on all five devices** (NA5C, G102, G6, MAX, P2P) as an even-width dashed
  line. No silent failures anywhere.
- **`setStrokeStyle` takes effect on a live session** — no `restartRawDrawing()` needed.
- **It survives the `HAND_WRITING_REPAINT_MODE` app-scope pin** production applies.
- **`setRawDrawingRenderEnabled(false)` does not clear the hardware buffer.** Removing the trail
  needs an explicit `EpdController.handwritingRepaint`.

## 3. What was already tried and failed

Both attempts are reverted; **do not repeat them.**

**Attempt 1 — `EpdController.startStroke/addStrokePoint/finishStroke`.** Every call returned cleanly
and drew nothing. `ReflectUtil.invokeMethodSafely` swallows a missing firmware method, so there is no
exception to catch. **Nothing in the Onyx SDK calls this API at all** — that check should have come
first.

**Attempt 2 — `EpdPenManager.startDrawing()` + `EpdController.moveTo/quadTo`.** This *is* the path
`SFTouchRender` uses internally. Also drew nothing from a context where `setRawDrawingEnabled(false)`.

**Attempt 3 — keep `setRawDrawingEnabled(true)` during lasso mode and leave `handleLassoTouch`
untouched.** The trail still did not render, **and** tap-to-dismiss and tool switching broke. That
breakage is the useful result: those are `MotionEvent`-driven, so enabling raw drawing evidently
starves the stylus `MotionEvent` stream that the entire lasso interaction depends on.

**Conclusion:** `setLassoMode(true)` calling `setRawDrawingEnabled(false)` is **load-bearing**, not
incidental. Hardware ink and MotionEvent-driven lasso logic appear to be mutually exclusive.

---

## 4. The core constraint

| | Hardware dashed trail | MotionEvent lasso logic |
|---|---|---|
| Raw drawing **off** (today) | ✗ | ✓ selection, drag, tap-dismiss, tool switching |
| Raw drawing **on** | ✓ in principle | ✗ all of the above break |

So the feature requires **migrating lasso input from `MotionEvent` to the raw callbacks** — which is
the most interaction-dense code in the app. That is the whole reason this needs a plan rather than a
patch.

---

## 5. Phase 0 — Characterise the input model *(do this first; it may cancel the rest)*

**No production code.** Extend `debug/PenToolSpikeActivity` instead.

The assumption that broke Attempt 3 was never measured. Measure it:

| Question | Why it matters |
|---|---|
| With `setRawDrawingEnabled(true)`, do **stylus** `MotionEvent`s still reach `onTouchEvent`? | If they do, Attempt 3 failed for some other reason and the migration is unnecessary |
| Do **finger** `MotionEvent`s still arrive? | Toolbar and tool switching depend on it |
| Does `onRawDrawingTouchPointMoveReceived` fire continuously mid-stroke, or only in batches? | Drag-move needs live position updates |
| Can `setRawDrawingRenderEnabled(false)` be toggled **mid-gesture**? | Needed to suppress ink once a drag is recognised |
| Does `handwritingRepaint` mid-gesture kill the in-progress stroke? | Needed for the tap/lasso transition |
| Can one pen-down→up produce **multiple** `onRawDrawingTouchPointListReceived` calls? | Observed once during the survey; a split gesture would corrupt the lasso path |

**Deliverable:** a table of answers in this file, plus a go/no-go.

**Best possible outcome:** stylus MotionEvents *do* survive, Attempt 3's failure was a state-restore
bug in `setLassoMode`, and Phase 1 alone ships the feature. **Test this before committing to
anything below.**

---

## 6. Phase 1 — Trail only, behind a flag

*Only if Phase 0 shows MotionEvents survive.*

- Add `LASSO_HW_DASH` build/debug flag, default **off**.
- In `setLassoMode(true)`: `setStrokeStyle(STROKE_STYLE_DASH)`, keep raw + render enabled.
- In `setLassoMode(false)`: restore `setStrokeStyle(0)` — **restore on mode exit, not gesture end**,
  so abandoning a gesture (page flip, toolbar tap) cannot leak the dash into normal ink.
- Stop painting `lassoOverlayPath` in `onDraw`; keep `lassoSelectionBox`.
- `handwritingRepaint` on gesture end to wipe the trail.
- Suppress render during drag-move so a moving selection does not trail dashes.

**Exit criteria:** trail renders dashed; selection, drag, tap-dismiss, tool switching, barrel-button
erase all unchanged with the flag on.

If this passes, **stop here** — phases 2–5 are unnecessary.

---

## 7. Phase 2 — Raw-driven gesture capture

*Only if Phase 0 says MotionEvents are starved.*

Build the lasso `Path` from the raw callbacks while leaving the downstream contract identical.

- `onBeginRawDrawing` → start gesture: record start point, clear selection, `lassoGestureHadSelection`.
- `onRawDrawingTouchPointMoveReceived` / `...ListReceived` → append to `lassoGesturePath`.
- `onEndRawDrawing` → close the path, call the **existing** `onLassoComplete(path, start)`.
- The mode guards in the raw callbacks (`if (isLassoMode ...) return`) invert for lasso.
- **Nothing may reach** `currentGesturePoints`, stroke saving, scribble-erase detection, or dwell.

**Exit criteria:** lassoing selects the correct objects, with a dashed hardware trail. Drag and taps
may still be broken at this phase — that is expected.

---

## 8. Phase 3 — Tap classification and dismissal

Port the UP-branch logic from `handleLassoTouch`:

- Tap vs lasso by **gesture bounds**, not net displacement (a small circular lasso returns near its
  origin — the existing comment explains why; preserve that reasoning).
- `onLassoTapToDismiss` when tapping away from a selection.
- `onLassoTap` for tap-inside-former-selection (shape transform, heading edit) and tap-to-paste.
- `lassoPreClearSelectionBox` / `lassoGestureHadSelection` bookkeeping.

**Exit criteria:** tap-away dismisses; tap-inside opens the right editor; tap-on-empty pastes.

---

## 9. Phase 4 — Drag-move

The largest phase. Currently keyed off `ACTION_DOWN` inside `lassoSelectionBox`.

- Inside-box hit test at `onBeginRawDrawing` using its `TouchPoint`.
- On drag recognition: `setRawDrawingRenderEnabled(false)` so the drag leaves no ink (verify
  mid-gesture toggling works — Phase 0).
- Port drag threshold, `onDragStarted`, `UpdateMode.GU_FAST` switch, snapping (`SnapEngine`),
  `dragBackingBitmap`, and `onStrokesMoved`.
- Sub-threshold drag must still fall through to `onLassoTap`.

**Exit criteria:** drag, snap guides, and undo/redo of moves all behave as before.

---

## 10. Phase 5 — Barrel-button erase

`onTouchEvent` currently intercepts `TOOL_TYPE_ERASER` / `BUTTON_STYLUS_PRIMARY` for lasso mode
**specifically because raw drawing is off** — the comment says so. With raw drawing on, the SDK
fires `onBeginRawErasing` instead.

- Re-home the interception onto the raw erasing callbacks for lasso mode.
- Verify against both the barrel button and an eraser-end stylus.

**Exit criteria:** barrel-button erase works during lasso mode exactly as before.

---

## 11. Phase 6 — Device validation, then flag removal

Validate on **all Tier-1 devices** (`.claude/skills/device-build-install`), because
`setStrokeStyle` failures are silent and firmware-dependent:

| Check | G102 | G6 | MAX | P2P | NA5C |
|---|---|---|---|---|---|
| Trail renders dashed | | | | | |
| Trail clears on pen-up | | | | | |
| Selection correct | | | | | |
| Drag + snap | | | | | |
| Tap-dismiss / tap-paste | | | | | |
| Barrel-button erase | | | | | |
| Tool switching | | | | | |
| **Normal ink still solid afterwards** | | | | | |
| Page flip mid-gesture | | | | | |

Then remove `LASSO_HW_DASH` and delete the MotionEvent lasso path — **not before**, so every phase
stays revertible by flipping one flag.

---

## 12. Abort criteria

Stop and revert if any of these hold:

- Phase 0 shows raw callbacks cannot deliver continuous mid-stroke positions (drag-move becomes
  unimplementable).
- Mid-gesture `setRawDrawingRenderEnabled` toggling does not work (no way to suppress drag ink).
- Any Tier-1 device fails the trail while others pass — a per-device fork is not worth it here.
- Phase 4 destabilises drag-move. **Drag is a daily-use interaction; a transient outline is not
  worth regressing it.**

## 13. Plan B — the cheaper win, if this is abandoned

Worth weighing **before** starting, because it may deliver most of the perceived benefit for a
fraction of the risk.

The current trail is not slow because it is an Android dash. It is slow because
`handleLassoTouch` **invalidates the entire canvas on a 60 ms throttle**
(`LASSO_REFRESH_INTERVAL_MS`) to extend one line — on a page with many strokes that is a full
recomposite per tick.

**Instead:** invalidate only the dirty rect of the newly added segment. Keeps every MotionEvent
behaviour intact, touches no interaction code, works on both engines, and needs no device matrix.

It will not look like a firmware dash — but "real-time feedback" was the actual goal, and this
addresses the actual cause.

---

## 14. Files and symbols

| File | Involved |
|---|---|
| `notebook/OnyxNotebookView.kt` | `setLassoMode`, `handleLassoTouch`, `rawInputCallback` (`onBeginRawDrawing` / `onEndRawDrawing` / `onRawDrawingTouchPointMoveReceived` / `onRawDrawingTouchPointListReceived`), `onDraw`, `onTouchEvent` barrel-button interception, `lassoPaint`, `lassoOverlayPath`, `lassoSelectionBox`, `openRawDrawing` |
| `NotebookActivity.kt` | `onLassoComplete` (~1444), `onLassoTapToDismiss` (~1563), `onLassoTap` (~1580), `onDragStarted` (~1617), `onLassoSelectionCleared` (~1622), `onStrokesMoved` (~1847) — **contracts must not change** |
| `notebook/GenericNotebookView.kt` | **Untouched.** Its own `handleLassoTouch` / `lassoPaint` stay |
| `debug/PenToolSpikeActivity.kt` | Phase 0 instrumentation |
| `docs/lasso-and-gestures.md` | Update on completion |
| `docs/drawing-engine.md` | EPD overlay rules — update if the lasso changes the handoff contract |

## 15. Notes for whoever picks this up

- **`setLassoMode(true)` disabling raw drawing is load-bearing.** That is the single most important
  thing on this page. It was read as an incidental detail in a prior attempt, and that assumption
  cost two rounds of device testing and a broken build.
- **Silence is the failure mode.** Onyx routes these calls through reflection into hidden framework
  methods and swallows failures, so a wrong API returns cleanly and draws nothing. Log on *success*,
  not only on failure, or a dead call looks like a healthy one.
- **Verify an SDK API is used by the SDK itself** before building on it. `startStroke` has no callers
  anywhere in the shipped AARs.
- The pen-activity gate (`markPenDown` / `markPenUp`) fires in the raw callbacks **before** the mode
  guard. Preserve that ordering — palm rejection depends on it.
