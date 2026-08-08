# Supernote (Ratta) Support — Implementation Plan

> **Branch:** `supernote` · **Targets:** Supernote Nomad + Supernote Manta
> **Overall status:** Phase 0 part 1 (adb interrogation) ✅ done — see its Findings. Part 2 (the
> probe app) not started. No production code written yet.

---

## Start-of-session checklist

This plan is written to survive a cleared context. At the start of every session:

1. Read this file top to bottom (it is the only Supernote document — there is no `docs/` companion
   until Phase 9).
2. Read **Phase status** below to find the first phase that is not `✅ DONE`.
3. Read that phase's section in full — it lists the files, the exact changes, the exit criteria, and
   the device-test script.
4. Re-read the **As-built code facts** section before touching any file. Line numbers in this plan
   are checked as of commit `be703f9` (2026-08-08); verify with `grep` rather than trusting them.
5. Build + install on **both** devices, hand the test script to the user, wait for the result.
6. On pass: tick the phase status here, commit, push. On fail: record the finding under the phase's
   **Findings** heading and iterate — do not advance the status.

**Do not skip ahead.** Each phase's exit criteria are what the next phase assumes to be true.

---

## Phase status

| # | Phase | Device test | Status |
|---|---|---|---|
| 0 | Baseline & firmware probe | ✅ yes | 🔧 IN PROGRESS — adb half ✅, probe app ⬜ |
| 1 | `SupernoteInk` binder client + live-ink proof | ✅ yes | ⬜ NOT STARTED |
| 2 | Device gate + engine factory (no behaviour change) | ✅ yes | ⬜ NOT STARTED |
| 3 | `RattaNotebookView` — core writing loop | ✅ yes | ⬜ NOT STARTED |
| 4 | Mode transitions & disable areas | ✅ yes | ⬜ NOT STARTED |
| 5 | Firmware dashed ink for the live lasso path | ✅ yes | ⬜ NOT STARTED |
| 6 | Lifecycle, page nav, snapshots, close | ✅ yes | ⬜ NOT STARTED |
| 7 | The remaining five drawing hosts | ✅ yes | ⬜ NOT STARTED |
| 8 | Ink mapping & EMR stroke-width tuning | ✅ yes | ⬜ NOT STARTED |
| 9 | Docs, device tiers, wrap-up | — | ⬜ NOT STARTED |

Legend: ⬜ NOT STARTED · 🔧 IN PROGRESS · 🧪 AWAITING DEVICE TEST · ✅ DONE · ⛔ BLOCKED

---

## Decisions (2026-08-08, before any code)

These were settled with the user at plan time. Do not re-litigate them mid-branch.

1. **Engine structure: sibling copy.** `RattaNotebookView.kt` is a **copy** of
   `GenericNotebookView.kt` with the live-ink parts edited. `GenericNotebookView` is **not touched**
   — no `open`, no `protected`, no extracted base class. This is deliberate: it gets us running on
   Ratta hardware with provably zero risk to the ten non-BOOX devices already shipping on the
   Generic engine. The user's words: *"I know this isn't the wisest… but for now, this gets us up and
   running on Ratta's hardware. We can review and make a better solution in a future effort."*
   Phase 9 files the follow-up (collapse Generic + Ratta into a `CanvasNotebookView` base) into
   `BACKLOG.md`.

2. **No fallback.** There is no kill switch and no mid-session engine swap. Engine choice is decided
   once, at construction: `isRattaDevice() && SupernoteInk.isAvailable()` → `RattaNotebookView`,
   otherwise the existing `isBooxDevice()` / Generic split. If the firmware misbehaves *after* that
   — a dead binder, a throwing transact, a missing `enableFullUiAuto` — we **log it and show a
   toast**, and keep running the Ratta view. The user's words: *"If something is broken, I want to
   know."* Failures must be loud, never silently papered over.
   - `Log.w` (survives release) for every firmware failure, not `Slog.d`.
   - One toast per Activity instance, so a broken binder doesn't spam a whole writing session.

3. **Host scope: notebook first, then all six.** The factory (Phase 2) routes all six drawing hosts
   from day one, but the firmware path is proven on `NotebookActivity` through Phases 3–5 and only
   then extended to the other five in Phase 7. The scratch pad and sticky-note editor are
   **translucent overlay windows** and are the likeliest place a firmware overlay misbehaves — they
   get their own test pass rather than riding along.

---

## As-built code facts

Checked against the tree at `be703f9`. **The previous version of this plan was wrong about all
three of these** — it was written before the drawing stack was refactored.

### Engine construction happens in SIX places, not one

| File | Line | Screen |
|---|---|---|
| `NotebookActivity.kt` | 1239 | the notebook (primary) |
| `CalendarActivity.kt` | 389 | Month/Week/Day canvas |
| `DayDetailActivity.kt` | 372 | day-window **Note** canvas |
| `ScratchpadActivity.kt` | 274 | scratch pad (translucent window) |
| `StickyNoteEditorActivity.kt` | 142 | sticky-note editor (translucent window) |
| `HwrEnrollmentActivity.kt` | 156 | TrOCR enrollment writing surface |

All six are the identical line:

```kotlin
drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
```

The old plan named `NotebookActivity.kt:641` as *the* selection site. Editing only that line would
leave five screens on the Generic engine. **Phase 2 replaces all six with a factory call.**

### `isBooxDevice()` lives in `core/Device.kt`

The whole file is six lines:

```kotlin
package com.notesprout.android.core
import android.os.Build
import java.util.Locale

fun isBooxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
```

`isRattaDevice()` goes here, next to it. The old plan pointed at `NotebookActivity.kt:6709`, which
is unrelated code.

### `HiddenApiBypass` is already in place

`NotesproutApplication.kt:60` (not `:33`) calls `HiddenApiBypass.addHiddenApiExemptions("")` before
anything else runs. That is the one prerequisite for reflecting into `android.os.ServiceManager` and
the hidden `eink` system service — no new setup needed.

### Generic's live-stroke path (the thing Ratta replaces)

`GenericNotebookView.onTouchEvent` — `ACTION_MOVE` appends points to `activePoints` and calls
`invalidate()` **per event batch** (line ~353). `onDraw` (line ~719) then re-blits the committed
`RenderNode` *and* re-strokes the whole in-progress polyline:

```kotlin
if (!isEraserActive && !isLassoMode && activePoints.size >= 2) {
    val path = Path()
    path.moveTo(activePoints[0].x, activePoints[0].y)
    for (i in 1 until activePoints.size) path.lineTo(activePoints[i].x, activePoints[i].y)
    strokePaint.color = penColorInt
    canvas.drawPath(path, strokePaint)
}
```

**That per-move `invalidate()` → full-view redraw → EPD panel update is the latency.** On Ratta both
the `invalidate()` and this `onDraw` block go away; the firmware paints the live stroke instead.

`ACTION_UP` (line ~363) is unchanged in spirit: append the final point, `commitActiveStroke()` (which
appends a `LiveStroke` and re-records the committed `RenderNode`), `invalidate()`, then
`checkAndDispatchGesture()` which runs the smart-lasso / scribble-erase gates and finally fires
`onPenLifted`. Ratta keeps all of it and adds one thing: clear the firmware overlay once the baked
stroke is on the panel.

### Debug spike activities are an established pattern

`app/src/debug/` already holds `ColorInkSpikeActivity`, `PenToolSpikeActivity`, `CryptoSpikeActivity`
etc., each registered `exported="true"` in `app/src/debug/AndroidManifest.xml` with a long comment
recording its findings, launched by:

```sh
adb -s <serial> shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.<Name>
```

Phases 0–1 use exactly this pattern (`SupernoteProbeActivity`). Findings get written into the
manifest comment, the same way the pen-tool and colour-ink spikes did.

---

## Prior art — `org.iccnet.supernotedemo` (the user's own app)

**Discovered during the Phase 0 adb probe, 2026-08-08.** A package built by the user is installed and
running on the Nomad, and it already contains a working Kotlin port of the ink client:

```
org/iccnet/supernotedemo/SupernoteInk.kt        ← Kotlin object, same shape as our Phase 1 target
org/iccnet/supernotedemo/SupernoteInk$Pen       ← NEEDLE 10 · MARK 11 · CALLIGRAPHY 15 · INK 16
org/iccnet/supernotedemo/SupernoteInk$Color     ← BLACK 0 · DARK_GRAY −101 · GRAY −102 · LIGHT_GRAY 254
org/iccnet/supernotedemo/DrawingView.kt         ← has a Tool enum (incl. ERASER)
org/iccnet/supernotedemo/MainActivity.kt
org/iccnet/supernotedemo/SupernoteDemoApp.kt    ← calls addHiddenApiExemptions
```

Read out of the installed APK's dex (constants are `PRIVATE STATIC FINAL`, values below are the
actual compiled values, not guesses):

| Constant | Value |
|---|---|
| `IFACE_TOKEN` | `"android.demo.IMyService"` |
| `APP_NAME` | `"supernote-demo"` |
| `TX_WRITE_APP_INFO` / `TX_DISABLE_AREA` / `TX_PEN` / `TX_DRAW_BUFFER` | 0 / 1 / 2 / 6 |
| Pen codes | 10 / 11 / 15 / 16 — identical to the Lua |
| Colour codes | 0 / −101 / −102 / 254 — identical to the Lua |

Its `SupernoteInk` API is a **superset** of what this plan's Phase 1 specifies:

```
isAvailable  lookupBinder  transact  claimPen  setPen  setEraser  clearAll
setDisableAreas  setFullScreenDisable  clearDisableAreas  enableFullUiAuto
enableAutoRegal  screenRefresh  sendOneFullFrame  einkApiDumped
```

— i.e. it also drives `enableAutoRegal`, `screenRefresh` and `sendOneFullFrame`, and carries an
`einkApiDumped` diagnostic that reflects the eink service's method list. Its error strings
(`"binder gone, marking unavailable"`, `"eink system service not present"`,
`"enableFullUiAuto unavailable: "`) show it already handles the failure paths the Lua guards.

**Implication for Phase 1:** if the source for this app is available, Phase 1 becomes a *port of
proven, on-device code* rather than a fresh translation of the Lua plus discovery. That is a large
de-risk and should be the first thing established at Phase 1 start. Ask the user.

**It also settles Risk 6 outright:** `APP_NAME` is `"supernote-demo"` — an arbitrary string the
firmware evidently accepts. The firmware does **not** whitelist app names, so `"notesprout"` is fine
and there is no need to masquerade as `"koreader-pencil"`.

---

## Reference material

### KOReader plugin source (local)

```
/Users/gregmarine/Downloads/koreader-supernote-eink-v1/plugins/pencil.koplugin/lib/supernote_ink.lua
```

Verified present. 255 lines of Lua/JNI. It is the primary spec for Phase 1 — port it call for call.

### Upstream Kotlin original

```
https://github.com/plateaukao/supernote_draw/blob/main/app/src/main/java/com/example/supernotedraw/SupernoteInk.kt
```

A decompile of the Supernote Document app's `HandWriteClient`. **The EMR size → stroke-width mapping
is missing from the Lua and lives only here** — pull it before Phase 8 (and ideally before Phase 3,
so the first ink is a sane width).

---

## How the Supernote ink path actually works

`supernote_ink.lua` is **not** a drawing engine — it is a thin **JNI client for a firmware-side ink
daemon**:

- The firmware registers a Binder service `service_myservice` (legacy alias `service.myservice`),
  interface token `android.demo.IMyService`.
- The app talks to it via raw `Parcel` / `IBinder.transact`. Every transaction writes
  `writeInterfaceToken(token)` + `writeString(appName)`, then a small int payload:

  | tx | Name | Payload | Purpose |
  |---|---|---|---|
  | 0 | `WRITE_APP_INFO` | `mode`, `value` | claim pen ownership |
  | 1 | `DISABLE_AREA` | `count`, then `x,y,w,h,flags` per rect | where the firmware must **not** paint |
  | 2 | `PEN` | `type`, `sizeEmr`, `color` | pen or eraser configuration |
  | 6 | `DRAW_BUFFER` | `255`, `0` | clear the EPDC ink overlay |

- Pen type codes (Nomad, deviceType = 3 / A5X2): Needle 10, Ink 16, Mark 11 (highlighter),
  Calligraphy 15. Eraser is written through **tx=2** as type `1` (freehand) or `3` (rectangular)
  with colour `255`.
- Colour codes: `BLACK 0`, `DARK_GRAY -101`, `GRAY -102`, `LIGHT_GRAY 254`. **Four values, that's
  the whole palette** (see the ink-mapping problem in Phase 8).
- `enableFullUiAuto(true)` via reflection on `getSystemService("eink")` — required so a
  *third-party* app gets ink painted everywhere, not just inside whitelisted firmware apps.

**The critical architectural fact:** the firmware paints stroke pixels to the EPDC overlay at
sub-frame latency, but it gives back **no point data**. KOReader configures the overlay, lets the
firmware paint live, then *clears the overlay once the finished stroke is baked into its own
buffer*. Points come from the normal Android input stream (`MotionEvent`).

**`DISABLE_AREA` is also the "firmware off" switch.** There is no explicit disable transaction —
`setFullScreenDisable(w, h)` sends a single rect covering the whole panel, which is how you stop the
firmware painting at all (dialogs, lasso mode, eraser mode). `clearDisableAreas()` sends a zero-rect
list to turn it back on. This is not spelled out in the Lua's naming and is easy to miss.

This maps onto our existing engine model:

| Concern | Onyx | **Ratta (new)** | Generic |
|---|---|---|---|
| Live-stroke ink | SDK overlay (`TouchHelper`) | **Firmware binder overlay** | App Canvas (slow on e-ink) |
| Point capture | `RawInputCallback` | **`MotionEvent` (onTouchEvent)** | `MotionEvent` |
| Bake + erase/lasso/text/snapshot | committed `RenderNode` | **committed `RenderNode`** | committed `RenderNode` |
| "Release the overlay" | `setRawDrawingRenderEnabled(false)` | **`clearAll()` + full-screen disable** | n/a |
| "Keep ink off the toolbar" | `setLimitRect(limit, exclusion)` | **`setDisableAreas([toolbar])`** | n/a (toolbar eats its own touches) |

Everything else — software erase hit-testing, lasso, smart-lasso / scribble detection, text
placement, shape transform, snapshot capture, `buildRenderBitmap` / `loadStrokesWithBitmap` — is
byte-for-byte the Generic logic and needs no change.

---

# Phases

---

## Phase 0 — Baseline & firmware probe · ⬜ NOT STARTED

**Goal:** answer "is the firmware path even available to us, and does the pen reach us as a stylus?"
before writing a line of engine code. Also establish the latency baseline we are trying to beat.

**Nothing in `src/main` changes in this phase.**

### Build

- `app/src/debug/kotlin/com/notesprout/android/debug/SupernoteProbeActivity.kt` — new. Read-only
  detection, results rendered as large on-screen text (the panel is the report; e-ink screenshots
  can't capture an EPD overlay, and BOOX/Ratta logcat ring buffers overflow — see
  `reference_boox_logcat_flood`). Report:
  - `Build.MANUFACTURER`, `Build.MODEL`, `Build.BRAND`, `Build.DEVICE`, SDK level
  - `ServiceManager.getService("service_myservice")` → present / null; same for `"service.myservice"`
  - `IBinder.getInterfaceDescriptor()` and `isBinderAlive()` on whichever resolved
  - `getSystemService("eink")` → present / null; class name; whether `enableFullUiAuto(boolean)`
    exists on it via reflection (do **not** call it yet)
  - a scratch strip that reports, for the last touch: `getToolType(0)`, `pressure`,
    `getAxisValue(AXIS_TILT)`, `buttonState`, and whether an eraser end / barrel button reports as
    `TOOL_TYPE_ERASER`
  - `DisplayMetrics` (w × h px, density) and the activity window's `getLocationOnScreen` offset for
    a full-bleed child view
- `app/src/debug/AndroidManifest.xml` — register it `exported="true"`, matching the existing spikes.

### Device test

```sh
cd apps/notesprout_android && ./gradlew assembleDebug
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # Nomad
adb -s <MANTA-SERIAL>  install -r app/build/outputs/apk/debug/app-debug.apk   # Manta
adb -s <serial> shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.SupernoteProbeActivity
```

Ask the user to, **on each device**:
1. Read back the probe report (photo or read-aloud).
2. Touch the scratch strip with the pen, the eraser end, and a finger; read back each tool type.
3. Then open a real notebook in the dev app, write a few lines, and describe the lag — this is the
   Generic-engine baseline we are trying to beat.

### Exit criteria

- [ ] The binder resolves on **both** devices (under either service name — record which).
- [ ] The `eink` service exists and exposes `enableFullUiAuto(boolean)` on both.
- [ ] The pen reports `TOOL_TYPE_STYLUS` (Generic's `onTouchEvent` rejects everything else — if the
      Supernote pen arrives as `TOOL_TYPE_FINGER`, the whole design changes and we stop here).
- [ ] Baseline writing latency described for both devices.
- [ ] Nomad and Manta report the same binder / service / tool-type story (the plan's premise that
      they are one target).

### Findings

#### Part 1 — adb interrogation (2026-08-08) ✅

Everything reachable over adb is **done**; only the runtime half (tool type, pressure, the binder
handshake, the pen-type sweep) still needs the probe app. Both devices attached and interrogated.

**Devices.** Serials: Nomad `SN078D10012852`, Manta `SN100C10023972`.

| Prop | Nomad | Manta |
|---|---|---|
| `ro.product.manufacturer` | `Supernote` | `Supernote` |
| `ro.product.model` | `Supernote Nomad` | **`Supernote Nomad`** ⚠️ |
| `ro.product.brand` / `.device` / `.name` | `Supernote` | `Supernote` |
| `ro.build.display.id` | `Chauvet.E103.2605211001.2347_release` | *identical* |
| Android | 11 (SDK 30) | 11 (SDK 30) |
| Physical size | 1404 × 1872 | 1920 × 2560 |
| Density | 300 | 300 |

Three things fall out of that table:

1. **`Build.MANUFACTURER` is `"Supernote"`, not `"ratta"`.** The `isRattaDevice()` sketch in Phase 2
   guessed `contains("ratta")` — that would return **false** on both devices. Match on
   `"supernote"` (manufacturer *or* brand; both carry it). Phase 2 has been corrected.
2. ⚠️ **The Manta identifies itself as a Nomad.** Every product property is byte-identical across the
   two devices — model included. **There is no name-based way to tell them apart**; the only
   difference visible to an app is the resolution (1404×1872 ≈ 7.8″ vs 1920×2560 ≈ 10.7″, both at
   300 dpi). Any future per-device branching must key off screen size, never `Build.MODEL`.
3. **They run the identical firmware build**, down to the build id — and have identical service
   tables (179 services each, `service_myservice` at the same index). This is much stronger evidence
   for the plan's "treat them as one target" premise than the KOReader author's inference was.

**The binder is present on both.** `service list` reports `service_myservice: []` on each. The empty
interface descriptor is expected and *good* — it means a raw `Binder`, not an AIDL stub, which is
exactly the `Parcel`/`transact` design the Lua implements. Only `service_myservice` is registered;
the legacy `service.myservice` alias is **not** present, so the first name always wins. No system app
under `/system/priv-app` or `/system/app` contains the token — the service is registered natively
(`ht.hardware.hteink@1.0-service` is the likely host), so there is nothing further to decompile and
the Lua remains the spec for the transaction payloads.

**The eink service is real, and richer than the Lua uses.** `eink: [android.os.IEinkManager]` is
registered, and `android.os.EinkManager` was dumped out of `/system/framework/framework.jar` with
`dexdump`. **Confirmed present, with exact signatures:**

```
enableFullUiAuto(Z)V            enableFullUiAuto(ZZ)V      ← the one the Lua needs, plus an overload
isValid()Z                      getEinkEnabled()Z
screenRefresh(ZI)V              sendOneFullFrame()V        freezeScreen(ZI)V
setScreenMode(IZ)V              setDitherType(I)V
enableAutoRegal(ZI)V            disableRegal(ZI)V
setStylusGuestureEnabled(Z)V    setGlobalGuestureEnabled(Z)V     [sic — "Guesture"]
```

with screen-mode constants `EINK_SCREEN_MODE_CLEAR / DEFAULT / SMOOTH / SPEED`.

Two of these are likely to matter later and are **not** in the Lua:

- **`setScreenMode(EINK_SCREEN_MODE_SPEED)`** looks like the Supernote analogue of the BOOX app-scope
  handwriting fast-mode that fixed our first-stroke lag (`docs/drawing-engine.md`). If Phase 3 shows
  a warm-up delay on the first stroke, this is the first thing to try.
- **`setStylusGuestureEnabled(false)`** may be needed to stop the firmware's own system stylus
  gestures competing with our lasso and scribble gestures.

**Prior art found.** `org.iccnet.supernotedemo` — see the *Prior art* section above. It changes how
Phase 1 should be approached.

#### Part 2 — probe app ⬜ NOT STARTED

_(still needed: pen `getToolType(0)`, pressure/tilt, eraser-end and barrel-button reporting, the
runtime binder handshake, and the 0…31 pen-type sweep)_

---

## Phase 1 — `SupernoteInk` binder client + live-ink proof · ⬜ NOT STARTED

**Goal:** the make-or-break phase. Port the Lua to Kotlin and prove the firmware will paint ink
inside *our* app window, then clear on command. If this fails, nothing downstream is worth building.

### Build

- `app/src/main/kotlin/com/notesprout/android/notebook/ratta/SupernoteInk.kt` — new Kotlin `object`.
  Port `supernote_ink.lua` call for call:
  - `getService` via reflection on `android.os.ServiceManager`; try both service names; cache the
    `IBinder`.
  - `private fun transact(code: Int, write: (Parcel) -> Unit)` mirroring the Lua's `transact`:
    `Parcel.obtain()` × 2 → `writeInterfaceToken(IFACE_TOKEN)` → `writeString(APP_NAME)` →
    `write(data)` → `binder.transact(code, data, reply, 0)` → `recycle()` both in a `finally`.
    Catch `DeadObjectException` → re-lookup once → on second failure mark unavailable and **report**
    (per Decision 2: `Log.w` + one toast, never a silent degrade).
  - Public API: `isAvailable(context)`, `claimPen()`, `setPen(type, sizeEmr, color)`,
    `setEraser(rectangular, sizeEmr)`, `clearAll()`, `setFullScreenDisable(w, h)`,
    `setDisableAreas(rects: List<Rect>)`, `clearDisableAreas()`, `enableFullUiAuto(activity, Boolean)`.
  - Constants: `Pen.NEEDLE/INK/MARK/CALLIGRAPHY`, `Color.BLACK/DARK_GRAY/GRAY/LIGHT_GRAY`, the four
    tx codes, `IFACE_TOKEN = "android.demo.IMyService"`, `APP_NAME = "notesprout"`.
  - `APP_NAME`: the Lua sends `"koreader-pencil"`. Unknown whether the firmware whitelists specific
    names. If Phase 1 fails to paint, **retrying with `"koreader-pencil"` is the first thing to
    try** — it is a known-working value.
  - Every failure path logs; no exception escapes into the drawing loop.
- `SupernoteProbeActivity` gains a driver panel: buttons for *Claim pen*, *Full UI auto on/off*,
  *Pen type −/+*, *Size −/+*, *Colour cycle*, *Eraser*, *Clear all*, *Disable top strip*,
  *Clear disable areas* — plus a plain white write area below them.

- **Pen-type sweep.** The Lua names only four codes (Needle 10, Ink 16, Mark 11, Calligraphy 15),
  but those are the four the *Supernote UI* exposes — the firmware's `penTypeArray` may hold more.
  Make *Pen type* a raw code stepper over **0…31**, not a four-item cycler, and have the user write a
  short stroke at each code, recording what renders (or nothing). This is the same sweep
  `PenToolSpikeActivity` ran against Onyx's overlay styles, which turned up three working styles past
  the SDK's own published constant list — including `STROKE_STYLE_DASH`.

  **What we're hunting for is a dashed / patterned stroke style**, because that unlocks Phase 5.
  Record the full table of code → appearance in Findings; it is also the raw material for a future
  pen-tool offering.

### Device test

Install both. On **each** device, in the probe:
1. Write in the white area **before** claiming the pen → note whether the firmware paints anyway.
2. *Claim pen* → *Full UI auto on* → write → **does firmware ink appear, and is it instant?**
3. *Clear all* → does the ink vanish?
4. Step the pen-type code from 0 to 31, writing a short stroke at each → which codes render, and
   what does each look like? **Is any of them dashed or patterned?**
5. Cycle sizes at the default type → does the stroke visibly change?
6. Cycle colours → what do the four codes actually look like on this panel?
7. *Disable top strip* → write across the strip → does ink stop at the boundary?
8. *Clear disable areas* → does it paint there again?
9. *Eraser* → write → what does the firmware draw (if anything)?
10. Leave the probe and return (home button, then relaunch) → does ink still work without re-claiming?

### Exit criteria

- [ ] Firmware paints live ink inside our window on both devices, visibly faster than the Phase 0
      baseline.
- [ ] `clearAll()` removes it completely, leaving no ghost.
- [ ] Disable areas actually suppress painting in the given rect (this is what protects the toolbar).
- [ ] The four colour codes are characterised (what grey each one actually renders).
- [ ] A usable default pen type + EMR size is chosen for Phase 3 (Ink 16 is the expected default).
- [ ] The 0…31 pen-type sweep is recorded, with an explicit yes/no on **whether any code renders a
      dashed or patterned stroke**. This decides whether Phase 5 is buildable — if nothing dashed
      exists, Phase 5 becomes ⛔ BLOCKED and the lasso keeps Phase 4's Canvas path.
- [ ] Nomad and Manta behave identically. **If Manta differs, record exactly how** — the plan's
      one-target premise is a claim from the KOReader author who never had a Manta.

### Findings

_(record here — especially the EMR size scale and what each colour code renders as)_

---

## Phase 2 — Device gate + engine factory · ⬜ NOT STARTED

**Goal:** one place decides the engine, and all six hosts use it. **Deliberately no behaviour
change** — this phase is a pure refactor that must leave BOOX and Generic devices byte-identical.
Doing it separately gives a clean regression checkpoint before any Ratta code exists.

### Build

- `core/Device.kt` — add:

  ```kotlin
  fun isRattaDevice(): Boolean =
      Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")
  ```

  **This is the measured string, not a guess** — Phase 0 confirmed `ro.product.manufacturer` is
  `Supernote` on both devices (`ro.product.brand` too, if a second check is ever wanted). Do **not**
  match on `"ratta"`: the company name appears nowhere in the build properties, and an earlier draft
  of this plan guessed it and would have returned false on both devices.

  Note the function keeps the `isRattaDevice` name for symmetry with `isBooxDevice` (both name the
  vendor, not the brand) — the *string* it matches is what had to change.

- `notebook/NotebookViewFactory.kt` — new:

  ```kotlin
  fun createNotebookView(context: Context): NotebookView = when {
      isBooxDevice()                    -> OnyxNotebookView(context)
      isRattaDevice() &&
          SupernoteInk.isAvailable(context) -> RattaNotebookView(context)
      else                              -> GenericNotebookView(context)
  }
  ```

  Ordered so BOOX is decided first (cheap, no reflection) and the binder probe only runs on a Ratta
  device. In **this** phase `RattaNotebookView` does not exist yet — the Ratta branch is commented
  out with a `TODO(Phase 3)` and the factory returns Generic there, so the refactor stays a no-op.

  Log the chosen engine once at construction (`Log.i`, not `Slog.d` — one line per screen open is
  worth having in a release bug report).

- Replace all six construction sites with `drawingView = createNotebookView(this)` and drop the
  now-unused `isBooxDevice` / `OnyxNotebookView` / `GenericNotebookView` imports from each.

### Device test

Install on: **G102** (flagship, BOOX regression), **Nomad**, **Manta**.

- G102: open a notebook, the calendar day-note, the scratch pad, a sticky note, and HWR enrollment.
  Write on each. Everything must behave exactly as before — this is a "nothing changed" test.
- Nomad + Manta: same five surfaces, still on the Generic engine, still working.

### Exit criteria

- [ ] No visible change anywhere on G102.
- [ ] No visible change on Nomad/Manta.
- [ ] `isRattaDevice()` returns true on both Supernotes (check the engine log line).

### Findings

_(record here)_

---

## Phase 3 — `RattaNotebookView`, core writing loop · ⬜ NOT STARTED

**Goal:** first real ink in a real notebook. Pen tool only — every other tool is Phase 4.

### Build

- `notebook/RattaNotebookView.kt` — **copy `GenericNotebookView.kt` verbatim**, rename the class,
  then make exactly these edits (per Decision 1; `GenericNotebookView.kt` is not touched):

  1. **`ACTION_DOWN`** — after the existing `activePoints` setup, configure and claim the firmware
     pen for this stroke (`setPen(INK, defaultSizeEmr, Color.BLACK)`). Keep the existing
     `invalidate()`; it is one frame at stroke start, not per move.
  2. **`ACTION_MOVE`** — keep collecting points into `activePoints` and keep the dwell tracking
     (`checkAndDispatchGesture` depends on it), but **delete the `invalidate()`**. This is the
     latency fix.
  3. **`onDraw`** — delete the in-progress-stroke block entirely (the `activePoints.size >= 2` path).
     The firmware owns live ink. Everything else in `onDraw` stays: drag layer, committed
     `RenderNode` blit, shape-transform overlay, lasso overlay.
  4. **`ACTION_UP`** — after `commitActiveStroke()` + `invalidate()`, clear the firmware overlay so
     the baked stroke is the only copy on screen. **Sequencing is the #1 risk in this phase** —
     `invalidate()` is asynchronous, so an immediate `clearAll()` wipes the overlay before our frame
     reaches the panel and the stroke visibly blinks out and back. Try in this order and record what
     works:
     - **(a)** `post { SupernoteInk.clearAll() }` — clear on the next looper turn.
     - **(b)** clear from a one-shot flag consumed at the end of `onDraw`, after the committed node
       has been blitted.
     - **(c)** `postDelayed(clearAll, N)` with the smallest N that looks clean — the crude fallback.
     Mirror the intent of Onyx's `setRawDrawingRenderEnabled(false)` → bitmap → repaint order.
  5. **Class header comment** — state plainly that this file is a copy of `GenericNotebookView`,
     what the six edits are, and that the two are slated to collapse into a shared base (BACKLOG).
     Someone fixing a lasso bug must know to fix it in both.

- `NotebookViewFactory.kt` — enable the Ratta branch.

- Firmware lifecycle, minimum viable version for this phase: `onAttachedToWindow` (after first
  layout, same `OnGlobalLayoutListener` shape Onyx uses) → `claimPen()` + `enableFullUiAuto(true)`.
  `onDetachedFromWindow` → `clearAll()` + `enableFullUiAuto(false)` + `clearDisableAreas()`.
  Anything subtler is Phase 6.

### Device test

Install both. In the dev app, open a notebook and **use the pen tool only**:

1. Write a sentence — is the ink instant, and does it match the Phase 1 probe?
2. Watch the moment the pen lifts — does the stroke stay put, blink, double-darken, or shift
   position? (A position shift means the screen-vs-view coordinate offset is wrong.)
3. Write near all four screen edges and in the corners.
4. Write across the toolbar — expected to be **wrong** in this phase (that's Phase 4); just record
   what happens.
5. Fill a page with ~30 strokes, then flip pages and come back — is every stroke there?
6. Draw a fast closed circle around some writing (smart lasso) and a scribble over some writing
   (scribble-erase) — both must still fire, since they run off `activePoints`, not the overlay.
7. Close the notebook, reopen it — is everything persisted?

### Exit criteria

- [ ] Live ink is instant and visually correct.
- [ ] Pen-lift handoff is clean — no blink, no double-darkening, no offset.
- [ ] Strokes persist across page flips and notebook close/reopen.
- [ ] Smart lasso and scribble-erase still fire.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here — especially which of (a)/(b)/(c) sequencing won)_

---

## Phase 4 — Mode transitions & disable areas · ⬜ NOT STARTED

**Goal:** every non-writing mode must take the firmware overlay away, exactly as Onyx's
`setRawDrawingEnabled(false)` table does. If the firmware keeps painting during lasso, its ink lands
on top of our dashed overlays and the page is unusable.

### Build — all in `RattaNotebookView.kt`

| Trigger | Firmware action |
|---|---|
| `setEraserMode(true)` | `clearAll()` + `setFullScreenDisable(w, h)`. Erase stays a software hit-test (already in the copy). Do **not** use the firmware eraser — its trail is not our eraser's geometry. |
| `setEraserMode(false)` | `clearDisableAreas()` + re-apply toolbar disable areas |
| `setLassoMode(true)` / `setLassoEraserMode(true)` | `clearAll()` + `setFullScreenDisable(w, h)` — the Canvas draws the dashed path. **Phase 5 revisits this**: if the firmware has a dashed stroke style, the live path becomes firmware ink instead. |
| `setLassoMode(false)` / `setLassoEraserMode(false)` | restore, as for eraser-off |
| `setTextPlacementMode(true)` | `clearAll()` + full-screen disable so the placement tap starts no ink |
| `enterShapeTransform` / `exitShapeTransform` | same pair — handles and the rotate knob are Canvas-drawn |
| `setDragMoveMode` | drag runs inside lasso mode, so it is already suppressed; verify no extra call is needed |
| `releaseRender()` (fires on **every** toolbar touch, from all six hosts) | `clearAll()` + `invalidate()` — the Ratta analogue of Onyx releasing the overlay so toolbar state becomes visible |
| `resetOverlay()` | `clearAll()` then re-arm |
| `setToolbarExclusion(rect)` | `setDisableAreas([rect])`. **Generic no-ops this** — Ratta must implement it. Convert view coords → screen coords via `getLocationOnScreen` before sending; the firmware paints in screen space. Null/empty ⇒ `clearDisableAreas()`. |

Note `setToolbarExclusion` is also how the **pen-colour panel** protects itself
(`penColorPanel.panelRectIn(drawingView.asView())` — see `ScratchpadActivity.kt:2158` and siblings),
so getting the coordinate conversion right covers both.

### Device test

Install both. In a notebook, for **each** of pen / eraser / lasso / lasso-eraser / insert-text /
shape-transform:
1. Switch to the tool, use it, switch away — does firmware ink appear where it shouldn't?
2. Specifically: in lasso mode, drag a selection — is the dashed box clean, with no firmware ink
   over it?
3. Write across the toolbar with the pen — ink must stop at the toolbar edge.
4. Open the pen-colour panel and drag the stylus across it — no ink on the panel.
5. Open the overflow menu, a text-edit dialog, and the page-index screen — no stray ink anywhere.
6. Erase some strokes — do they disappear cleanly with no firmware residue?

### Exit criteria

- [ ] No firmware ink over the toolbar, the colour panel, any dialog, or any menu.
- [ ] Lasso / lasso-eraser / text-placement / shape-transform overlays render clean.
- [ ] Every tool switch leaves no stale overlay ink.
- [ ] Returning to the pen restores instant firmware ink.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here)_

---

## Phase 5 — Firmware dashed ink for the live lasso path · ⬜ NOT STARTED

> **Gated on Phase 0/1's pen-type sweep.** If no firmware pen code renders a dashed or patterned
> stroke, mark this phase ⛔ BLOCKED, leave Phase 4's Canvas path in place, and move on. Everything
> downstream is independent of it.

**Goal:** the lasso outline should be *drawn by the stylus in hardware*, at pen speed, the same way
ink is — not chased by a software Canvas path at e-ink redraw rates.

Today every engine draws the lasso with `lassoPaint` — a Canvas `Paint` with
`DashPathEffect(floatArrayOf(12f, 8f), 0f)` — refreshed at most every `LASSO_REFRESH_INTERVAL_MS`
(60 ms). That throttle *is* the look the user dislikes: the dashed line visibly trails the pen.
The Onyx SDK has a real hardware dashed stroke (`STROKE_STYLE_DASH = 5`, confirmed rendering on all
five BOOX devices by `PenToolSpikeActivity` — see `docs/onyx-pen-tools.md`) that we have never
leveraged. **Doing it on Supernote from day one is the point of this phase**; the Onyx equivalent is
filed to `BACKLOG.md` in Phase 9.

### Scope — what can and cannot move to the firmware

The firmware only paints **where the pen is touching**. So:

| Lasso visual | Drawn by | Can it be firmware ink? |
|---|---|---|
| Live outline while the stylus is down (`lassoOverlayPath`) | stylus contact | ✅ **yes — this phase** |
| Lasso-eraser's jittered grey trail (`lassoEraserDisplayPath`) | stylus contact | ✅ **yes — this phase** |
| Dashed selection box after lift (`lassoSelectionBox`) | no contact | ❌ stays Canvas |
| Drag-move preview (backing bitmap + translated objects + box) | contact, but it's a *drag*, not a trace | ❌ stays Canvas |
| Snap guides | no contact | ❌ stays Canvas |

So this is precisely the thing the user called out — the line being drawn under the stylus — and
nothing more.

### Build — `RattaNotebookView.kt`

1. **Lasso mode no longer full-screen-disables.** Replace Phase 4's blanket suppression for
   `setLassoMode(true)` / `setLassoEraserMode(true)` with: arm the firmware pen at the dashed code
   found in Phase 1 (lasso-eraser: whichever code best matches its grey trail), keep disable areas on
   the toolbar only.
2. **`handleLassoTouch` / `handleLassoEraserTouch` `ACTION_MOVE`** — keep building `lassoGesturePath`
   (the hit test needs the real geometry), but stop assigning `lassoOverlayPath` /
   `lassoEraserDisplayPath` and drop the throttled `invalidate()`. The firmware paints the trace.
   The `LASSO_REFRESH_INTERVAL_MS` throttle becomes dead code on this path — that is the win.
3. **`ACTION_UP`** — `clearAll()` to wipe the firmware trace, then the existing flow runs unchanged:
   hit test → `onLassoComplete` → the host calls `setLassoOverlay(null, box)` → Canvas draws the
   selection box. Same sequencing risk as the Phase 3 pen-lift handoff; reuse whichever of (a)/(b)/(c)
   won there.
4. **Drag-move** is entered from `ACTION_DOWN` inside an existing selection box. That branch must
   suppress firmware ink immediately (`setFullScreenDisable`) — a drag must not leave a dashed trail
   — and restore on `ACTION_UP`.
5. **`setLassoOverlay` / `setLassoSelectedIds`** — no firmware involvement; they only set the box.

### Device test

Install both. In a notebook:
1. Lasso some writing — **does the dashed outline keep up with the pen?** Compare against the same
   gesture on the G102 (the current software look) if one is to hand.
2. Lasso quickly and in a tight circle — does the trace break up or lag?
3. On lift — does the trace vanish cleanly and the dashed selection box appear in its place, with no
   double image?
4. Drag the selection — no dashed trail follows the pen; the box and objects move as before.
5. Tap outside to dismiss; lasso again — no stale trace.
6. Smart lasso (fast closed circle in **pen** mode) still selects — it must not have picked up the
   dashed pen.
7. Lasso-eraser: draw across some strokes — trail keeps up, strokes erase, no residue.
8. Lasso, then switch tools mid-gesture via the toolbar — nothing left painted.

### Exit criteria

- [ ] The live lasso outline is drawn in hardware and keeps pace with the stylus.
- [ ] Lift → box handoff is clean, no double image, no residue.
- [ ] Drag-move leaves no trail.
- [ ] Smart lasso and scribble-erase (pen mode) are unaffected.
- [ ] Lasso-eraser trail likewise.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here — including which firmware pen code was used for each of the two trails)_

---

## Phase 6 — Lifecycle, page nav, snapshots, close · ⬜ NOT STARTED

**Goal:** the overlay must never outlive its page. A firmware overlay lingering across a page flip
paints the old page's ink onto the new one.

### Build — `RattaNotebookView.kt`

- `onWindowFocusChanged(false)` → `clearAll()` + `setFullScreenDisable(w, h)` + `enableFullUiAuto(false)`.
  On regain → `enableFullUiAuto(true)` + `claimPen()` + `clearDisableAreas()` + re-apply toolbar
  disable areas. (Onyx treats focus as unreliable on e-ink and leans on `resumeDrawing()` instead —
  expect the same here and wire both.)
- `resumeDrawing()` (called from every host's `onResume`) → re-claim + re-arm. This is the reliable
  path; treat focus as a bonus.
- `disableDrawing()` → full-screen disable. `enableDrawing()` → restore.
- `releaseForHandoff()` (called before launching another drawing screen) → `clearAll()` +
  `enableFullUiAuto(false)` + `clearDisableAreas()`. Unlike Onyx there is no process-global
  single-owner pipeline to protect, but the *panel overlay* is shared state, so hand it over clean.
- `clearForPageLoad()` / `eraseAll()` / `loadStrokesWithBitmap()` / `setTemplate()` → `clearAll()`
  **before** the content swap, so no old-page ink survives onto the new page.
- `releaseResources()` → `clearAll()` + `enableFullUiAuto(false)` + `clearDisableAreas()`, then the
  inherited `RenderNode`/bitmap teardown.
- `captureSnapshot()` needs no firmware call (it renders from `strokes`, never from the screen), but
  verify the cover it produces has no missing final stroke.

### Device test

Install both. In a notebook:
1. Write on page 1, flip to page 2 — is page 2 clean? Flip back — is page 1 intact?
2. Write, then immediately flip before the ink settles.
3. Write, press home, return — ink still works, no stale overlay.
4. Write, lock the screen, unlock.
5. Write, open the scratch pad from the toolbar, come back.
6. Write, open a sticky note, come back.
7. Write, close the notebook, check the library cover thumbnail includes the last stroke.
8. Insert a page, delete a page, change the page template.
9. Two-finger swipe down to Today, then back.

### Exit criteria

- [ ] No overlay ink ever survives a page change.
- [ ] Ink still works after every background/foreground round trip above.
- [ ] Covers/snapshots include the final stroke.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here)_

---

## Phase 7 — The remaining five drawing hosts · ⬜ NOT STARTED

**Goal:** extend the proven path to calendar, day-note, scratch pad, sticky editor, and HWR
enrollment. The factory already routes them (Phase 2); this phase is about the host-specific quirks.

**The two translucent hosts are the risk.** `ScratchpadActivity` and `StickyNoteEditorActivity` are
overlay windows over the notebook. A firmware overlay that paints in screen space knows nothing
about our window stack, so ink may land in the wrong place or bleed under the host. Expect real work
here; it is why they got their own phase.

### Build

Per host: confirm the toolbar/chrome rect reaches `setToolbarExclusion` in **screen** coords (each
host computes it differently), confirm `releaseRender()` and `resumeDrawing()` are wired, and handle
any window-offset correction the translucent hosts need.

### Device test

Install both. On each of the five surfaces: write, switch tools, use lasso, use the eraser, leave and
return, and (scratch pad / sticky note) transfer content back to the page.

Additionally for the calendar: Month, Week and Day views, plus the full-view export.

### Exit criteria

- [ ] Firmware ink works on all five, positioned correctly.
- [ ] No ink bleeds outside the translucent hosts' windows.
- [ ] Content transfer (scratch pad → page, sticky → page) is unaffected.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here)_

---

## Phase 8 — Ink mapping & EMR stroke-width tuning · ⬜ NOT STARTED

**Goal:** make the live firmware ink *look like* the ink we bake. Until now Phase 3 has hardcoded
black at a default width.

**This is the problem the old plan missed entirely.** v1.2 shipped colour ink: the palette offers 16
greys **and** 16 colours (`core/InkColor.kt`, `notebook/PenPalette.kt`), and the armed colour is
pushed to the engine via `setPenColor(hex)` from `PenColorPreferences`. The Supernote firmware pen
accepts **four** colour codes — `BLACK 0`, `DARK_GRAY -101`, `GRAY -102`, `LIGHT_GRAY 254` — on a
greyscale panel. So:

- The **baked stroke keeps its true stored hex**, exactly as on every other device. Nothing about
  the data model changes; a note written on a Nomad opens in colour on a NoteAir5C.
- The **live firmware overlay** gets the nearest of the four codes, chosen by luminance. Since the
  panel is greyscale, its rendering of a colour is a grey anyway — the mapping only has to match
  what the panel would have shown.
- Build the mapping off the Phase 1 findings (what each code actually renders), not off theory.

Also in this phase:
- Pull the **EMR size → stroke-width mapping** from the upstream `supernote_draw` repo and map our
  stroke width (`LiveStroke.DEFAULT_STROKE_WIDTH`; Generic paints at 2.5f, Onyx at 3.0f) onto it, so
  the live overlay's thickness matches the baked polyline.
- Pen type: decide whether Ink (16) or Needle (10) better matches our round-cap polyline.

### Build

- `RattaNotebookView.setPenColor(hex)` — keep the stored hex for baking (inherited behaviour), and
  additionally arm the firmware with the mapped code.
- A small mapping helper next to `SupernoteInk` — `fun firmwareColorFor(hex: String): Int` — with the
  measured luminance thresholds as documented constants.
- Width mapping constant(s), likewise documented with where the numbers came from.

### Device test

Install both. In a notebook:
1. Write with black; compare live ink to baked ink at the moment of lift — same weight, same darkness?
2. Step through all 16 greys — does the live ink track the baked ink plausibly?
3. Step through several colours — does the live grey match what the baked stroke renders as?
4. Write fast and slow — does the width look consistent?

### Exit criteria

- [ ] Live and baked ink are visually indistinguishable at pen-lift for black.
- [ ] Every palette entry produces a sensible live grey with no jarring jump.
- [ ] Stroke width matches.
- [ ] Identical on Nomad and Manta.

### Findings

_(record here)_

---

## Phase 9 — Docs, device tiers, wrap-up · ⬜ NOT STARTED

No device test. Documentation and housekeeping.

- `docs/drawing-engine.md` — add Ratta to the engine table (line ~10 lists the engines, line ~84 the
  pen-activity hook table). Document the firmware overlay model, the disable-area mechanism, the
  mode-transition table from Phase 4, and the pen-lift sequencing decided in Phase 3.
- `CLAUDE.md` — add Ratta to the drawing-engine row of the docs table; note the sibling-copy
  arrangement so nobody fixes a lasso bug in only one of the two files.
- `README.md` (line ~36 "Future") and `.claude/skills/device-build-install/SKILL.md` — move Supernote
  Nomad & Manta out of **Future** into a tier, and add the Manta serial to the device table. **These
  two lists drift — change both.**
- `BACKLOG.md` — file the deferred work:
  - collapse `GenericNotebookView` + `RattaNotebookView` into a shared `CanvasNotebookView` base
    (the explicit Decision-1 follow-up)
  - **Onyx: draw the live lasso outline with the SDK's hardware `STROKE_STYLE_DASH` (= 5)** instead
    of the software `DashPathEffect` Canvas path. This is the BOOX half of Phase 5 — the user has
    explicitly said the current software look is not what they want. `PenToolSpikeActivity` already
    proved `DASH` renders on all five BOOX devices and that `setStrokeStyle` needs no
    `restartRawDrawing` and survives the handwriting fast-mode pin (`docs/onyx-pen-tools.md`), so the
    research is done — this is a build task, not a spike. Whatever Phase 5 learns about the
    lift → selection-box handoff applies directly.
  - firmware pen types beyond the default (Needle / Mark / Calligraphy, plus whatever the Phase 1
    sweep turns up) as a possible pen-tool offering, alongside the Onyx equivalents in
    `docs/onyx-pen-tools.md`
  - anything Phases 0–8 punted
- Decide the fate of `SupernoteProbeActivity`: keep it (like `PenToolSpikeActivity`, as the
  calibration tool for EMR width and colour codes) with its findings written into the debug manifest
  comment. Recommended: **keep**.
- Delete this file once merged, per the project's convention of retiring completed plans to git
  history — **after** its content is in `docs/`.

---

## Risks / open questions

1. **Reverse-engineered, firmware-specific.** ~~Manta is *believed* to share firmware with the
   Nomad~~ — **Phase 0 measured this and it holds**: both devices run the byte-identical firmware
   build `Chauvet.E103.2605211001.2347_release` with identical 179-entry service tables. The
   one-target premise is now evidence, not inference. Phase 1's exit criteria still require the two
   to match behaviourally, since identical firmware does not guarantee identical *panel* behaviour.
   ⚠️ **New trap from the same measurement: the Manta reports `Build.MODEL` as `Supernote Nomad`.**
   The two are indistinguishable by name — branch on screen size or not at all.
2. **Pen-lift sequencing** (Phase 3, edit 4) is the most likely thing to look wrong first: clear too
   early and the stroke blinks, clear too late and it double-darkens. Three candidate orderings are
   written into the phase.
3. **Screen-space vs view-space coordinates.** The firmware paints in screen coordinates; every
   `MotionEvent` we bake is in view coordinates. Disable areas must be converted via
   `getLocationOnScreen`. A mismatch shows up as ink that stops at the wrong boundary, or a baked
   stroke that jumps on pen-lift.
4. **Translucent hosts** (scratch pad, sticky editor) — a screen-space overlay under a translucent
   window is untested territory. Phase 7.
5. ~~**`enableFullUiAuto` may be absent**~~ — **resolved in Phase 0.** `android.os.EinkManager`
   on this firmware exposes both `enableFullUiAuto(Z)V` and `enableFullUiAuto(ZZ)V`, dumped from
   `framework.jar`. Keep the guard anyway (it costs nothing and protects future firmware), but this
   is no longer an open risk. Per Decision 2, a failure logs + toasts rather than degrading.
6. ~~**`APP_NAME` may be whitelisted**~~ — **resolved in Phase 0.** The user's own
   `org.iccnet.supernotedemo` sends `"supernote-demo"` and works, so the firmware accepts arbitrary
   names. `"notesprout"` is fine.
7. **No point data from the firmware.** Everything we persist comes from `MotionEvent`. If the
   Supernote's `MotionEvent` stream is lower-resolution than the firmware's own sampling, baked
   strokes will look slightly coarser than the live ink did. Watch for it in Phase 3.

### Open — needs the user

- ~~**Manta ADB serial is unknown.**~~ **Resolved 2026-08-08: `SN100C10023972`.** Added to
  `.claude/skills/device-build-install/SKILL.md` alongside the Nomad's `SN078D10012852`. The skill's
  entry carries the "identifies as a Nomad" warning, since serial is the only reliable way to tell
  the two apart from the host side. (Phase 9 still covers the tier move.)
- **Is the source for `org.iccnet.supernotedemo` available?** See *Prior art*. If yes, Phase 1 is a
  port of working code instead of a fresh translation. **Ask before starting Phase 1.**
