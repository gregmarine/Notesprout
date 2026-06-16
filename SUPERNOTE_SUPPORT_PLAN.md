# Supernote (Ratta) Support — Implementation Plan

> **Status:** Planned, not started. This document captures the design so we can pick it up later.

## Reference: KOReader plugin source

The entire design is reverse-engineered from a KOReader plugin that lights up the
Supernote firmware ink path. Local copy of the key file:

```
/Users/gregmarine/Downloads/koreader-supernote-eink-v1/plugins/pencil.koplugin/lib/supernote_ink.lua
```

That Lua file is itself a port of the Kotlin original (the decompiled Supernote
Document app's `HandWriteClient`):

```
https://github.com/plateaukao/supernote_draw/blob/main/app/src/main/java/com/example/supernotedraw/SupernoteInk.kt
```

The EMR size→stroke-width mapping (missing from the Lua) lives in that upstream
`supernote_draw` repo — pull it from there when tuning Phase 1.

---

## How the Supernote ink path actually works

`supernote_ink.lua` is **not** a drawing engine — it's a thin **JNI client for a
firmware-side ink daemon**. The mechanism:

- Supernote firmware registers a Binder service `service_myservice` (alias
  `service.myservice`), interface token `android.demo.IMyService`.
- The app talks to it via raw `Parcel`/`transact` calls. Every transaction writes
  `writeInterfaceToken(token)` + `writeString(appName)`, then a small int payload:
  - **tx=0** `WRITE_APP_INFO` — claim pen ownership
  - **tx=1** `DISABLE_AREA` — rects where the firmware must *not* paint (our toolbar)
  - **tx=2** `PEN` — pen/eraser type, EMR size, color (Needle 10 / Ink 16 / Mark 11 / Calligraphy 15; black = 0)
  - **tx=6** `DRAW_BUFFER` — clear the EPDC ink overlay
- `enableFullUiAuto(true)` via reflection on `getSystemService("eink")` — required so
  a *third-party* app gets ink painted everywhere, not just whitelisted firmware apps.

**The critical architectural fact:** the firmware paints stroke pixels to the EPDC
overlay at sub-frame latency, but it gives back **no point data**. KOReader configures
the overlay, lets the firmware paint live, then *clears the overlay once the finished
stroke is baked into its own buffer*. Points come from the normal Android input stream
(`MotionEvent`).

This maps almost perfectly onto our existing two-engine model:

| Concern | Onyx | **Ratta (new)** | Generic |
|---|---|---|---|
| Live-stroke ink | SDK overlay (`TouchHelper`) | **Firmware binder overlay** | App Canvas (slow on e-ink) |
| Point capture | `RawInputCallback` | **`MotionEvent` (onTouchEvent)** | `MotionEvent` |
| Bake + erase/lasso/text/snapshot | App Canvas bitmap | **App Canvas bitmap** | App Canvas bitmap |

**`RattaNotebookView` = `GenericNotebookView`'s MotionEvent capture + bitmap model,
with the firmware overlay handling live ink.** The *only* behavioral difference from
Generic is the live-stroke phase: Generic draws `activePoints` per `ACTION_MOVE`
(laggy on e-ink); Ratta suppresses that, lets the firmware paint, and on `ACTION_UP`
bakes the stroke into `renderBitmap` then calls `clearAll()` to wipe the overlay. This
is exactly Onyx's `renderStroke()` → bitmap handoff, just with a firmware binder
instead of the Onyx SDK.

Everything else — software erase hit-testing, lasso, smart-lasso/scribble detection,
text placement, snapshot capture, `buildRenderBitmap`/`loadStrokesWithBitmap` — is
byte-for-byte the Generic logic and needs no change.

---

## Implementation Plan

### Phase 0 — Baseline (already works)

Supernote (`Build.MANUFACTURER` ≈ `"ratta"`) currently falls through `isBooxDevice()`
to `GenericNotebookView` (`NotebookActivity.kt:641`) and is functional today, just with
e-ink-laggy ink. RattaNotebookView is a **latency upgrade**, not a correctness fix — so
it can be built and validated incrementally with a safe fallback.

### Phase 1 — `SupernoteInk` Kotlin binder client (`notebook/ratta/SupernoteInk.kt`)

Port the Lua to a Kotlin `object`. We already have
`HiddenApiBypass.addHiddenApiExemptions("")` in `NotesproutApplication.kt:33`, which is
the one prerequisite.

- `getService` via reflection on `android.os.ServiceManager` (hidden API); cache the `IBinder`.
- `transact(code) { Parcel.writeInt... }` helper mirroring Lua's `transact` —
  `writeInterfaceToken` + `writeString(appName)` preamble, `IBinder.transact`, swallow
  `DeadObjectException` and re-lookup, recycle parcels.
- Public API: `isAvailable(context)`, `claimPen()`, `setPen(type,size,color)`,
  `setEraser(...)`, `clearAll()`, `setFullScreenDisable(w,h)` / `setDisableAreas(rects)`
  / `clearDisableAreas()`, `enableFullUiAuto(activity, Boolean)`.
- Pen-type/EMR-size constants from the Lua. **Flag:** size→EMR mapping isn't in the Lua
  (it's in the upstream `supernote_draw` repo); start with a sane Ink default ≈ our
  3.0f stroke and tune on-device.

### Phase 2 — `RattaNotebookView` (`notebook/RattaNotebookView.kt`)

Recommended structure: **extract a `CanvasNotebookView` abstract base from
`GenericNotebookView`**, then `Generic` and `Ratta` both extend it. ~90% of
GenericNotebookView (lasso, erase, snapshot, render helpers, bitmap build) is shared;
only these hooks differ for Ratta:

- **Suppress live active-stroke draw** — in `onDraw`, skip the `activePoints` path
  (firmware owns live ink). Override the `ACTION_MOVE` branch to *not* `invalidate()`
  per move.
- **`ACTION_UP`**: `commitActiveStroke()` (bakes into `renderBitmap`) →
  `SupernoteInk.clearAll()` → `invalidate()`. The baked bitmap replaces the firmware
  overlay one-to-one; sequencing matters to avoid a flash (mirror Onyx's
  `setRawDrawingRenderEnabled(false)` → bitmap → repaint order).
- **Coordinate alignment**: firmware paints in *screen* coords; `MotionEvent` is *view*
  coords. Use `setDisableAreas([toolbar rect])` to keep firmware ink off the toolbar —
  the direct analog of Onyx's `setLimitRect(limit, exclusion)` in
  `OnyxNotebookView.applyLimitRect()`. Account for the view's `getLocationOnScreen` offset.

If extracting a base class is too invasive for a first cut, fall back to a sibling copy
(matches how Onyx/Generic already duplicate). Prefer the base-class refactor since it
eliminates a third copy of the lasso/erase/snapshot code.

### Phase 3 — Engine selection (`NotebookActivity.kt:641`)

```kotlin
drawingView = when {
    isBooxDevice()                 -> OnyxNotebookView(this)
    SupernoteInk.isAvailable(this) -> RattaNotebookView(this)   // binder present
    else                           -> GenericNotebookView(this)
}
```

Gate on **binder availability**, not just `Build.MANUFACTURER` — exactly the Lua's
self-detection philosophy. A Supernote without the binder safely uses Generic. Add
`isRattaDevice()` next to `isBooxDevice()` (`NotebookActivity.kt:6709`) as a cheap
pre-filter before the JNI probe.

### Phase 4 — Mode transitions & overlay handoff

Each non-writing transition must release the firmware overlay, identical in spirit to
Onyx's `setRawDrawingEnabled(false)` table in CLAUDE.md:

- **Eraser mode**: don't claim firmware pen; handle erase via MotionEvent (software
  hit-test, already in Generic); `clearAll()` so no stale overlay ink.
- **Lasso / lasso-eraser / text-placement**: `clearAll()` + stop claiming pen so the
  firmware doesn't paint over the app-drawn dashed overlays; the Canvas renders them
  (Generic already does this).
- **`releaseRender()`** (toolbar touch): `clearAll()` — the Ratta equivalent of Onyx
  releasing the overlay so toolbar state is visible.
- **`onWindowFocusChanged(false)`**: capture snapshot (Generic already does) +
  `clearAll()` + release pen claim. On regain: re-`claimPen()` + `enableFullUiAuto(true)`
  + re-apply disable areas.

### Phase 5 — Lifecycle, snapshot, close

- `onAttachedToWindow` / first layout: `claimPen()`, `enableFullUiAuto(true)`,
  `setDisableAreas([toolbar])`.
- Snapshot capture, page navigation, and close paths are unchanged from Generic (all
  bitmap-based) — just add a `clearAll()` before any bitmap swap so the firmware overlay
  never lingers over a freshly loaded page.
- `releaseResources` / detach: `enableFullUiAuto(false)` + `clearDisableAreas()` to
  relinquish the firmware politely.

---

## Risks / open questions to validate on-device

1. **Reverse-engineered, firmware-specific.** Pen codes are confirmed for **Nomad
   (deviceType = 3 / A5X2)**. **Manta uses the same firmware and base chipset as Nomad —
   only the screen size differs** (the KOReader plugin author simply lacked a Manta to
   test on). So whatever works for Nomad is expected to work for Manta unchanged. We have
   **both devices** for testing, so validate on both but treat them as one target.
2. **EMR size→stroke-width mapping** is unspecified in the Lua (needs the upstream
   `supernote_draw` source or on-device tuning).
3. **Coordinate/offset alignment** between firmware screen-space painting and the app's
   view-space baked bitmap — the toolbar disable-area and `getLocationOnScreen` offset
   are the main things to get right; a mismatch shows as a baked stroke "jumping" on
   pen-lift.
4. **`enableFullUiAuto`** may be absent on some firmwares (the Lua already guards for
   this) — degrade to Generic if it throws.

---

## Suggested starting point

Phase 1 (the `SupernoteInk` Kotlin port) is self-contained and testable in isolation —
good first step. Alternatively, sketch the `CanvasNotebookView` base-class extraction
first so Phase 2 has a clean seam.
