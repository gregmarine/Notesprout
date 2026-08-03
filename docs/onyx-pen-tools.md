# Onyx Pen SDK — Writing Tool Capabilities (Research)

> Referenced from `CLAUDE.md`. **Research only — nothing here is planned or scheduled.** This is a
> capability survey of what the Onyx SDK actually offers for writing tools (pen kinds, widths,
> pressure, tilt, texture), written so a future session can plan a tool picker without re-deriving
> the SDK surface.
>
> Notesprout today arms exactly one tool: `setStrokeWidth(3.0f)` + `setStrokeColor(...)`, and never
> calls `setStrokeStyle` at all. Everything below is unused headroom.

## Where these facts come from

The public Onyx documentation is **years behind the shipped binaries** and is not a usable source.
[`OnyxAndroidDemo/doc/Onyx-Pen-SDK.md`](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md)
still documents `onyxsdk-pen:1.2.1`, names only **two** stroke styles (`STROKE_STYLE_FOUNTAIN`,
`STROKE_STYLE_PENCIL`), and does not mention `setStrokeColor`, `NeoPenConfig`, or any of the
`NeoPen*` renderer classes. The demo repo has no NeoPen sample.

Everything in this document was instead read out of the **decompiled AARs in the Gradle cache**
(`javap -p -c -l`) at the exact versions this app builds against:

| Artifact | Version we use | Latest on `repo.boox.com` (2026-08-01) | Role |
|---|---|---|---|
| `com.onyx.android.sdk:onyxsdk-pen` | **1.5.4** | 1.5.4.1 | `TouchHelper`, raw input, EPD overlay plumbing, `NeoPenRender` |
| `com.onyx.android.sdk:onyxsdk-penbrush` | **1.1.1** (transitive) | 1.1.1.1 | The `NeoPen` family — every software pen renderer |
| `com.onyx.android.sdk:onyxsdk-pennative` | **1.0.4** (transitive) | — | `libneopen_jni.so`, the native stroke solver |
| `com.onyx.android.sdk:onyxsdk-base` | **1.8.5** (transitive) | — | `TouchPoint`, `NoteShapeType`, `PenTexture`, `NoteConstant` |
| `com.onyx.android.sdk:onyxsdk-device` | **1.3.3** (resolves 1.3.4) | — | `EpdController` → firmware |

**Note for future work:** `onyxsdk-penbrush` and `onyxsdk-pennative` are already `compile`-scope
transitive dependencies of `onyxsdk-pen:1.5.4`. Every software pen renderer described in this
document is **already on our classpath and already in our APK** — using them would add no new Gradle
dependency (`libneopen_jni.so` ships `arm64-v8a` + `armeabi-v7a`; our `abiFilters` keeps arm64-v8a).

---

## The single most important structural fact: there are two render paths

An Onyx stroke is drawn **twice**, by two completely different engines, and the SDK gives you
separate controls for each:

| | **Path A — live EPD overlay** | **Path B — software repaint** |
|---|---|---|
| Who draws | BOOX **firmware**, straight to the panel, bypassing Android's view system | Your own `Canvas`, via SDK `NeoPen*` classes |
| When | While the pen is down | On pen-up / page load / any redraw |
| API | `TouchHelper.setStrokeStyle/Width/Color` → `EpdController` | `NeoPen` + `NeoPenConfig` + `NeoPenRender`, or the `*Wrapper.drawStroke()` helpers |
| Configurable | style int, width float, ARGB colour — **that is the entire surface** | ~30 fields: pressure curve, tilt, velocity, brush shape/ratio/angle, spacing, smoothing, alpha |
| Runs where | Firmware; unsupported values silently do nothing | In-process; deterministic, testable, device-independent |

Notesprout currently uses Path A for the live stroke and **plain `Paint`/`Path` polylines** for
Path B (`OnyxNotebookView.drawStrokePath` — one `strokeWidth`, `Cap.ROUND`, no pressure). The two
paths already disagree slightly today; any richer tool set has to keep them in agreement, because
the user sees Path A while writing and Path B forever after.

---

## Path A — `TouchHelper` / `EpdController` (the live overlay)

### Stroke style constants

`TouchHelper.setStrokeStyle(int style)`. The same values are mirrored on
`com.onyx.android.sdk.pen.style.StrokeStyle`:

| Value | `TouchHelper` constant | `StrokeStyle` constant | `EpdController` constant | Note |
|---:|---|---|---|---|
| 0 | `STROKE_STYLE_PENCIL` | `PENCIL` | `STROKE_STYLE_PENCIL` | |
| 1 | `STROKE_STYLE_FOUNTAIN` | `FOUNTAIN` | `STROKE_STYLE_BRUSH` | **Name collision — see below** |
| 2 | `STROKE_STYLE_MARKER` | `MARKER` | `STROKE_STYLE_MARKER` | |
| 3 | `STROKE_STYLE_NEO_BRUSH` | `NEO_BRUSH` | `STROKE_STYLE_NEO_BRUSH` | |
| 4 | `STROKE_STYLE_CHARCOAL` | `CHARCOAL` | `STROKE_STYLE_CHARCOAL` | |
| 5 | `STROKE_STYLE_DASH` | `DASH` | *(none)* | |
| 6 | `STROKE_STYLE_CHARCOAL_V2` | `CHARCOAL_V2` | *(none)* | |
| 7 | `STROKE_STYLE_SQUARE_PEN` | `SQUARE_PEN` | *(none)* | |
| 8 | *(none)* | `SOFT_ERASER` | *(none)* | Only on `StrokeStyle` |

**The name collision at value 1 is real and worth remembering.** The device layer calls style `1`
`STROKE_STYLE_BRUSH`; the pen layer calls the identical int `STROKE_STYLE_FOUNTAIN`. They are the
same firmware mode. `EpdController` knows only styles **0–4**; the pen SDK exposes **0–7 (8)**.

### What `setStrokeStyle` actually does — verified by bytecode

There is **no translation table and no software fallback**. The call is a pass-through to firmware:

```
TouchHelper.setStrokeStyle(style)
  → for each TouchRender in the helper's render list:
      SFTouchRender.setStrokeStyle(style)  → EpdPenManager.setStrokeStyle(style)
                                           → EpdController.setStrokeStyle(style)
      AppTouchRender.setStrokeStyle(style) → EpdController.setStrokeStyle(style)   (direct)
  → EpdController.setStrokeStyle(int) = Device.currentDevice().setStrokeStyle(int)
  → BaseDevice.setStrokeStyle(int) { return }                       // no-op default
  → SDMDevice / RK33XXDevice override:
       ReflectUtil.invokeMethodSafely(cachedMethod, null, Integer.valueOf(style))
```

Consequences that matter:

- The int is handed **verbatim** to a reflected hidden framework method. Styles 5/6/7 are meaningful
  only if that device's firmware understands them. Nothing in the SDK validates the value.
- `ReflectUtil.invokeMethodSafely` swallows failures. On an unsupported style you get **no
  exception, no return value, no log** — just the previous style, or nothing.
- `BaseDevice` is a no-op, so on any non-Onyx device the whole call silently disappears.
- **Therefore per-device capability has to be established empirically.** There is no
  `isStrokeStyleSupported()` anywhere in the SDK. (Tier-1 devices — G102, G6, MAX, P2P — would each
  need a visual check.)

### Width and colour routing

`setStrokeWidth(float w)` and `setStrokeColor(int argb)` fan out over the same render list, but land
in different places depending on which `TouchRender` is active:

| | `SFTouchRender` (BOOX/stylus path) | `AppTouchRender` (fallback path) |
|---|---|---|
| `setStrokeWidth` | `RawInputManager` → `RawInputReader.setStrokeWidth` → `nativeSetStrokeWidth()` **and** `EpdController.setStrokeWidth()` | `AppTouchInputReader.setStrokeWidth()` **and** `EpdController.setStrokeWidth()` |
| `setStrokeColor` | `EpdPenManager.setStrokeColor` → `EpdController.setStrokeColor` **and** `RawInputReader.setStrokeColor` | `EpdController.setStrokeColor` only |
| `setStrokeStyle` | `EpdPenManager` → `EpdController` | `EpdController` |

Width is a raw `float`, not dp and not clamped — the SDK imposes no minimum or maximum. (BOOX's own
Notes app exposes [25 discrete widths and 16 colours](https://shop.boox.com/blogs/news/what-makes-boox-a-powerful-note-taker);
that ladder is a product decision, not an SDK constraint.)

### Which render path we get

`TouchHelper.create(view, callback)` — the overload `OnyxNotebookView` uses — picks the feature set
itself:

```java
int feature = DeviceFeatureUtil.hasStylus(view.getContext())
        ? FEATURE_SF_TOUCH_RENDER    // 2  → SFTouchRender
        : FEATURE_APP_TOUCH_RENDER;  // 1  → AppTouchRender
```

| Constant | Value | Renderer |
|---|---:|---|
| `FEATURE_APP_TOUCH_RENDER` | 1 | `AppTouchRender` — MotionEvent-driven |
| `FEATURE_SF_TOUCH_RENDER` | 2 | `SFTouchRender` — SurfaceFlinger/raw-input overlay (**what BOOX uses**) |
| `FEATURE_APP_PEN_TOUCH_RENDER` | 4 | `AppPenTouchRender` (extends `AppTouchRender`) |
| `FEATURE_ALL_TOUCH_RENDER` | 3 | APP \| SF — note this does **not** include 4 |

`TouchHelper.create(view, feature, callback[, touchListenerEnabled])` lets you pick explicitly.

### Eraser styles

`setEraserRawDrawingEnabled(boolean drawing, int eraserStyle)` — the second argument is an eraser
style int, again pass-through. `StrokeStyle.SOFT_ERASER = 8` is the only named eraser constant in the
pen SDK. `onyxsdk-base` separately defines `NoteShapeType.ERASER_STROKE = 0`, `ERASER_MOVE = 1`,
`ERASER_AREA = 2` (BOOX Notes' three eraser modes).

---

## Path B — the `NeoPen` family (software rendering)

This is the large, genuinely configurable half of the SDK, and it is almost entirely undocumented
publicly. It renders to any `Canvas`, so it works identically on BOOX, on a generic tablet, and in
an export bitmap.

### The ten pen types

`NeoPenConfig.type` (identical constants on `com.onyx.android.sdk.pennative.PenConfig`):

| Value | Constant | Implementation class | Result type |
|---:|---|---|---|
| 1 | `NEOPEN_PEN_TYPE_BRUSH` | `NeoBrushPen` | `PenPointResult` (variable-size points) |
| 2 | `NEOPEN_PEN_TYPE_FOUNTAIN` | `NeoFountainPen` | `PenPointResult` |
| 3 | `NEOPEN_PEN_TYPE_MARKER` | `NeoMarkerPen` | `PenPointResult` |
| 4 | `NEOPEN_PEN_TYPE_CHARCOAL` | `NeoCharcoalPen` | `PenTextureResult` (bitmap stamps) |
| 5 | `NEOPEN_PEN_TYPE_CHARCOAL_V2` | `NeoCharcoalPenV2` | `PenTextureResult` |
| 6 | `NEOPEN_PEN_TYPE_FOUNTAIN_V2` | `NeoFountainPenV2` | `PenPointResult` — holds its own `NeoPenConfig` |
| 7 | `NEOPEN_PEN_TYPE_PENCIL` | `NeoPencilPen` | `PenBrushResult` (masked grain stamps) |
| 8 | `NEOPEN_PEN_TYPE_BALLPOINT` | `NeoBallpointInkPen` (`NeoBallpointPen.create(fastMode)`) | `PenPathResult` |
| 9 | `NEOPEN_PEN_TYPE_SQUARE` | `NeoSquarePen` | `PenPointResult` — flat/calligraphic nib |
| 10 | `NEOPEN_PEN_TYPE_BRUSH_SIGN` | *(no dedicated class in 1.1.1)* | — |

Non-native pens also exist for simple cases: `NeoSinglePathResultPen` (one `Path` for the whole
stroke), `NeoSegmentPathResultPen` (per-segment paths), `NeoMarkerPenV2` (extends
`NeoSinglePathResultPen`).

### `NeoPenConfig` — the full field list, with real defaults

Constructed via `new NeoPenConfig()`; fields are public and there are fluent setters for a subset.
Defaults below are read from the constructor bytecode (fields not listed default to `0`/`false`):

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `int` | `1` (BRUSH) | Pen type constant above |
| `color` | `int` | `0xFF000000` | ARGB |
| `width` | `float` | `3.0` | Nominal stroke width |
| `minWidth` | `float` | `0.001` | Width floor at minimum pressure |
| `fastMode` | `boolean` | `false` | Cheaper solve; fewer output points |
| `rotateAngle` | `int` | `0` | Nib rotation (square/calligraphy) |
| `tiltEnabled` | `boolean` | `false` | Feed stylus tilt into width/shape |
| `tiltScale` | `float` | `3.0` | Tilt gain (`TILT_SCALE_VALUE` const is `5.0`) |
| `directionEnabled` | `boolean` | `false` | Stroke direction affects nib orientation |
| `maxTouchPressure` | `float` | `1.0` | Pressure normalizer — feed `EpdController.getMaxTouchPressure()` |
| `dpi` | `float` | `320.0` | Screen DPI |
| `displayScaleX` / `displayScaleY` | `float` | `1.0` / `1.0` | Display scale |
| `scalePrecision` | `float` | `1.0` | Zoom precision; `NeoPenConfig.Companion.getPrecision(f)` computes it |
| `brushSpacing` | `float` | `0.25` | Gap between stamps (`PenUtils.DEFAULT_BRUSH_SPACING`) |
| `brushShape` | `int` | `0` (CIRCLE) | `NEOPEN_BRUSH_SHAPE_CIRCLE`=0, `ELLIPSE`=1, `RECTANGLE`=2 |
| `brushRatio` | `float` | `5.0` | Stamp aspect ratio |
| `brushAngle` | `float` | `0.0` | Stamp angle in degrees |
| `pressureSensitivity` | `float` | `0.3` | Pressure→width curve |
| `velocitySensitivity` | `float` | `0.5` | Velocity→width curve |
| `smoothLevel` | `float` | `0.6` | Path smoothing |
| `velocityAmplifier` | `float` | `0.0` | Velocity→width multiplier |
| `velocityIgnoreThreshold` | `float` | `0.0` | Below this, ignore velocity |
| `velocityLowerBound` / `velocityUpperBound` | `float` | `0.0` | Velocity clamp |
| `startPointLimit` | `float` | `0.0` | Start-of-stroke shaping |
| `startLengthLimit` | `float` | `0.0` | Start-of-stroke shaping |
| `endVelocitySensitivity` | `float` | `0.0` | End-of-stroke taper |
| `alphaFactor` | `float` | `1.0` | Opacity multiplier (private; getter/setter) |
| `brushShapes` | `List<Bitmap>` | `null` | Custom stamp bitmaps (pencil/charcoal) |

`NeoPenConfig.toNativeConfig()` converts to `pennative.PenConfig`, which carries **two extra fields
not present on `NeoPenConfig`**: `endThinningRate` and `ignorePressure`. Those are reachable only by
building a `PenConfig` directly and calling `NeoPenNative.createPen(type, config)`.

`resetBrushRatioAndAngle()` restores `brushRatio`/`brushAngle` to type-appropriate values.

### Per-pen default configs shipped by the SDK

Only three pens expose a `defaultPenConfig()`; the rest take a config you build:

```kotlin
NeoPencilPen.defaultPenConfig()      // type=7, minWidth=1.0f, brushSpacing=0.25f, pressureSensitivity=0.3f
NeoBallpointInkPen.defaultPenConfig()// type=8, smoothLevel=0.6f
NeoSquarePen.defaultPenConfig()      // type=9, directionEnabled=false, brushShape=RECTANGLE(2),
                                     //         brushRatio=10.0f, brushAngle=45.0f
```

`NeoSquarePen.NEO_SQUARE_PEN_DEFAULT_BRUSH_RATION = 10.0f` is the named constant for that ratio.
`FountainShapes.createNeoPenV2(width, minWidth, displayScaleX, displayScaleY, scalePrecision,
createScale, pressureSensitivity: Float?, fastMode: Boolean, smoothLevel: Float?)` is the
purpose-built factory for the fountain-v2 pen (parameter names preserved in the AAR).

### Tuning constants worth copying rather than inventing

From `com.onyx.android.sdk.pen.utils.PenUtils` (penbrush):

| Constant | Value |
|---|---:|
| `DEFAULT_BRUSH_SPACING` | `0.25` |
| `MIN_PRESSURE` | `0.001` |
| `DEFAULT_PRESSURE_SENSITIVITY` | `0.375` (range `0.15`–`0.6`) |
| `DEFAULT_VELOCITY_SENSITIVITY` | `0.5` |
| `DEFAULT_SMOOTH_LEVEL` | `0.6` |
| `DEFAULT_ALPHA_FACTOR` | `1.0` |
| `DEFAULT_DPI` | `320.0` |
| `MIN_PRECISION` / `MIDDLE_PRECISION` / `MAX_PRECISION` | `1.0` / `4.0` / `8.0` |
| `KEPLER_*_PRESSURE_SENSITIVITY` | min `0.0`, default `0.3`, max `1.0` |
| `KEPLER_*_SMOOTH_LEVEL` | min `0.0`, max `1.0` |

`toNormalizedPressureSensitivity(f)` / `toPercentPressureSensitivity(f)` convert between the
0.15–0.6 internal range and a 0–1 UI slider.

From `com.onyx.android.sdk.data.note.NoteConstant` (base) — the width multipliers BOOX Notes itself
applies so different pens *feel* like the same nominal width:

| Constant | Value |
|---|---:|
| `BRUSH_STROKE_WIDTH_EXTRA_SCALE` | `2.0` |
| `CHARCOAL_STROKE_WIDTH_EXTRA_SCALE` | `5.0` |
| `PEN_STROKE_WIDTH_WITH_TILT_EXTRA_SCALE` | `3.0` |
| `FILL_WIDTH_EXTRA_SCALE` | `2.0` |
| `MIN_STROKE_WIDTH_FOR_ENABLE_ANTI_ALIAS` | `2.0` |
| `COMMON_PEN_RESUME_DELAY_TIME_MS` | `150` |
| `COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS` | `500` |
| `COMMON_DEVICE_QUIT_FAST_MODE_DELAY_TIME_MS` | `5000` |

`MIN_FOUNTAIN_PEN_WIDTH = 1.0f` lives on `NeoFountainPenWrapper`.

### How a `NeoPen` is driven

```
NeoPen (abstract, holds a native penHandle)
  ├─ onPenDown(TouchPoint, predict: Boolean) : Pair<PenResult, PenResult>   // (real, prediction)
  ├─ onPenMove(List<TouchPoint>, TouchPoint) : Pair<PenResult, PenResult>
  ├─ onPenUp(TouchPoint, predict) : Pair<PenResult, PenResult>
  └─ destroy()
```

Each call returns `(realInk, predictionInk)` — the second is the *speculative* forward extrapolation
used to hide latency. `NeoPenRender` wraps a `NeoPen` and manages result accumulation:

- `NeoPenRender(neoPen)` · `render(canvas, paint)` · `render(canvas, paint, points)`
- `onTouchDown/onTouchMove/onTouchDone`, `onTouchPointList(points)`, `onTouchData(TouchData)`
- `loadPenPointArrays(): FloatArray` / `loadPenPointSizeArrays(): IntArray` — extract the solved
  geometry, e.g. to persist a rasterized-width stroke
- `POINT_LIST_BATCH_LIMIT = 1000`, `DEFAULT_POINT_COUNT_THRESHOLD = 100`
- `reset()` / `resetPredict()` / `destroyPen()`

Subclasses: `PencilNeoPenRender` (tracks `brushPointCount`), `CharcoalNeoPenRender`,
`NeoPenRenderWrapper` (adds prediction append + a `segment` flag), `BallpointPenRenderWrapper`.

### `PenResult` — the four geometry shapes a pen can emit

All four expose `getRect(): RectF`, `append(PenResult)`, `draw(Canvas, Paint)`, `clearCache()`:

| Class | Payload | Drawn as |
|---|---|---|
| `PenPathResult` | `Path` + `points: FloatArray` + `pointSizeArray: IntArray` | Path fill/stroke |
| `PenPointResult` | `List<PenPointInk(x, y, size)>` | Per-point dabs of varying size |
| `PenBrushResult` | `List<PenBrushInk(x, y, size: UByte, angle36: UByte, alpha: UByte)>` + a `BrushMaskGenerator` | Alpha-masked bitmap stamps |
| `PenTextureResult` | `List<PenTextureInk(x, y, bitmap)>` | Bitmap blits |

`PenBrushResult` details: `POINT_SIZE_FACTOR = 255.0f`, alpha clamped to `[4, 255]`, minimum point
size `2`, and it caches a `PaintHolder` per (size, alpha) pair. It also supports `matrix`,
`pointSizeScale`, and `isEnabledClipRect` for zoomed rendering.

### How the pencil texture is actually produced

This answers "Pencil … texture has 2 variations" from the SDK side, and it is more interesting than
a flag:

- `onyxsdk-penbrush` ships exactly one asset: **`res/drawable/pencil.png`, a 256×256 8-bit RGBA
  greyscale graphite blob.** That is the entire grain vocabulary.
- `NeoPencilPen.Companion.prepareRotatedBitmaps()` decodes it once and pre-rotates it into **36
  variants** (10° apart), cached in a `ConcurrentHashMap<Int, Bitmap>`.
- Per output point the native solver returns a `PenBrushInk` carrying `angle36` (which rotation) plus
  a `size` and `alpha` byte. `BrushMaskGenerator.getMaskBitmap(MaskKey(size, angle))` memoizes the
  scaled+rotated mask, and `PenBrushResult.drawMask()` blits it.
- So "pencil texture" = *stamp a rotated, scaled, alpha-modulated grain bitmap along the solved
  path*. Substituting `NeoPenConfig.brushShapes: List<Bitmap>` replaces the grain entirely.

The **two texture variations** the BOOX UI offers are a separate, explicit enum in `onyxsdk-base`:

```java
com.onyx.android.sdk.data.note.PenTexture {
    int CHARCOAL_SHAPE_V1 = 1;
    int CHARCOAL_SHAPE_V2 = 2;
}
```

carried on a stroke via `PenAttrs.setTexture(int)` / `getTexture()`, which is what
`ShapeCreateArgs.setPenAttrs(...)` and `PenArgs.setAttrs(...)` transport. V1/V2 correspond to
`NeoCharcoalPen` (type 4) vs `NeoCharcoalPenV2` (type 5) — i.e. **the "texture" toggle selects
between two whole pen implementations**, not a parameter on one.

### Charcoal rendering is bitmap-pool based

`NeoCharcoalPenWrapper` / `NeoCharcoalPenV2Wrapper` take a `PenRenderArgs` plus a caller-supplied
`List<Bitmap>` pool:

```java
NeoRenderPoint[] computeStrokeRenderPoints(PenRenderArgs renderArgs, List<Bitmap> pixelBitmapPool);
float[]          computeStrokePoints(PenRenderArgs renderArgs, List<Bitmap> pixelBitmapPool);
void             drawNormalStroke(PenRenderArgs renderArgs);   // ≤ threshold points
void             drawBigStroke(PenRenderArgs renderArgs);      // long strokes; batched
```

`PenRenderArgs` is a fluent bag: `canvas`, `paint`, `points`, `penType`, `color`, `strokeWidth`,
`createArgs: ShapeCreateArgs`, `screenMatrix`, `renderMatrix`, `contentRect`, `tiltEnabled`, `erase`.
`NeoRenderPoint` is `{ x, y, size, bitmapIndex }` — an index into that bitmap pool.
The V1 wrapper batches at 5000/1000 points; V2 at 5000/500.

### The easy path: three one-call software renderers

For fountain / brush / marker there is no need to touch `NeoPen` at all. `onyxsdk-pen` ships static
helpers that build the pen, solve the stroke, and draw it in a single call (parameter names below are
preserved in the AAR, not guessed):

```java
NeoFountainPenWrapper.drawStroke(Canvas canvas, Paint paint, List<TouchPoint> points,
                                 float displayScale, float strokeWidth,
                                 float maxTouchPressure, boolean erase);

NeoBrushPenWrapper.drawStroke(Canvas canvas, Paint paint, List<TouchPoint> points,
                              float strokeWidth, float maxTouchPressure, boolean erase);

NeoMarkerPenWrapper.drawStroke(Canvas canvas, Paint paint, List<TouchPoint> list,
                               float strokeWidth, boolean erase);
```

Each also exposes `computeStrokePoints(...)` returning a `List<TouchPoint>` whose `size` field is the
solved per-point width — useful if you want to persist the solved geometry instead of re-solving.
`NeoFountainPenWrapper.hasPressure(points)` reports whether a captured stroke carries usable
pressure. All three route through `NeoPenUtils.computeStrokePoints(type, points, strokeWidth,
maxTouchPressure)`, which dispatches to `NeoMarkerPen` / `NeoFountainPen` / `NeoBrushPen`
`Companion.create(config)` — i.e. the legacy-looking wrappers are thin shims over the modern native
pens, and are fully supported.

`PenUtils.drawStrokeByPointSize(canvas, paint, points, erase)` is the shared rasterizer underneath.

### One trap in this package

`com.onyx.android.sdk.pen.NeoPenWrapper` (note: **not** `NeoPenUtils`) is a **static, process-global,
single-pen** legacy API whose static initializer does `System.loadLibrary("neo_pen")`. **`libneo_pen.so`
is not shipped in any Onyx AAR** — it exists only inside BOOX firmware/the stock Notes app. Calling
`NeoPenWrapper` from a third-party app will throw `UnsatisfiedLinkError`. The supported native entry
point is `com.onyx.android.sdk.pennative.NeoPenNative`, which loads `neopen_jni` — and *that* library
**is** bundled in `onyxsdk-pennative`. `NeoPenConfigWrapper` is the matching legacy config (only 7
fields) and should likewise be ignored in favour of `NeoPenConfig`.

---

## What the input layer already gives us

`RawInputCallback` (which `OnyxNotebookView` already implements) delivers
`com.onyx.android.sdk.data.note.TouchPoint`, which extends `com.onyx.android.sdk.base.data.TouchPoint`:

```kotlin
class TouchPoint {
    var x: Float
    var y: Float
    var pressure: Float      // raw; normalize against EpdController.getMaxTouchPressure()
    var size: Float          // contact size / solved width, depending on producer
    var tiltX: Int
    var tiltY: Int
    var timestamp: Long
}
```

**Pressure and tilt are already arriving on every point we receive** — `onRawDrawingTouchPointListReceived`
gets a `TouchPointList` of these. Notesprout reads only `x`/`y` today (`OnyxNotebookView.kt:435`).

Supporting API:

- `EpdController.getMaxTouchPressure()` — the divisor for normalizing `pressure`; every `NeoPen`
  helper wants it as `maxTouchPressure`.
- `EpdController.getTouchWidth()` / `getTouchHeight()` — digitizer resolution (differs from screen).
- `TouchPointList` — `getPoints()`, `getRenderPoints()`, `applyMatrix`, `scaleAllPoints`,
  `translateAllPoints`, `rotateAllPoints`, `mirrorAllPoints`, `toTinyPointList()`,
  `getBoundingRect(points)`.
- `TouchPoint.size2Tilt(int)` / `tilt2Size(int, int)` — BOOX packs tilt into the size channel in its
  own compact `TinyPoint` format; these are the converters.
- `RawInputCallback.onPenActive(TouchPoint)` and `onPenUpRefresh(RectF)` are optional overrides.
- Events on the SDK bus: `PenActiveEvent`, `PenDeactivateEvent`, `PenDownPointLostEvent`
  (`TouchHelper.register/unregister(Any)`, `TouchHelper.getEventBusHolder()`).

`TouchPoint.OBJECT_BYTE_COUNT = 32` — the SDK's own per-point wire size, for comparison with our
8 bytes/point in `StrokeCodec`.

---

## Mapping the BOOX Notes tool list onto the SDK

The tool set visible in BOOX's own Notes app maps onto SDK constants as follows. The `NoteShapeType`
column is BOOX Notes' *persisted* tool identifier (from `onyxsdk-base`) — useful as evidence of intent,
and as a compatibility reference if we ever import BOOX notes.

| BOOX Notes UI tool | `NoteShapeType` | Overlay style (Path A) | Software pen (Path B) | Extra settings BOOX exposes |
|---|---:|---|---|---|
| **Pen** | `SHAPE_PENCIL_SCRIBBLE` = 2 | `STROKE_STYLE_PENCIL` = 0 | — (plain solver) | width, pressure sensitivity |
| **Pen → Calligraphy** | `SHAPE_LATIN_CALLIGRAPHY_PEN_SCRIBBLE` = 60 / `SHAPE_SQUARE_PEN` = 47 | `STROKE_STYLE_SQUARE_PEN` = 7 | `NEOPEN_PEN_TYPE_SQUARE` = 9 | width |
| **Pen → Fountain** | `SHAPE_FOUNTAIN_PEN_SCRIBBLE` = 4 | `STROKE_STYLE_FOUNTAIN` = 1 | `NEOPEN_PEN_TYPE_FOUNTAIN(_V2)` = 2 / 6 | width |
| **Brush Pen** | `SHAPE_NEO_BRUSH` = 21 (`SHAPE_BRUSH_SCRIBBLE` = 5 legacy) | `STROKE_STYLE_NEO_BRUSH` = 3 | `NEOPEN_PEN_TYPE_BRUSH` = 1 | width |
| **Ballpoint Pen** | `SHAPE_OILY_PEN_SCRIBBLE` = 3 | *(no overlay style)* | `NEOPEN_PEN_TYPE_BALLPOINT` = 8 | width |
| **Pencil** | `SHAPE_CHARCOAL_SCRIBBLE` = 22 | `STROKE_STYLE_CHARCOAL` = 4 / `CHARCOAL_V2` = 6 | `NEOPEN_PEN_TYPE_PENCIL` = 7, `CHARCOAL` = 4, `CHARCOAL_V2` = 5 | width + **texture** (`PenTexture.CHARCOAL_SHAPE_V1/V2`) |
| **Marker** | `SHAPE_MARKER_SCRIBBLE` = 15 | `STROKE_STYLE_MARKER` = 2 | `NEOPEN_PEN_TYPE_MARKER` = 3 | width |
| *(also present)* | — | `STROKE_STYLE_DASH` = 5 | — | dashed stroke |
| *(also present)* | `SHAPE_ASIA_CALLIGRAPHY_PEN_SCRIBBLE` = 61 | — | `NEOPEN_PEN_TYPE_BRUSH_SIGN` = 10 | — |

Notes on this mapping:

- The names do **not** line up cleanly across layers. BOOX's UI "Pencil" is the SDK's *charcoal*
  family; the SDK's `STROKE_STYLE_PENCIL` is what the UI calls plain "Pen". BOOX's "Ballpoint" is
  internally *oily pen*. Do not assume a constant means what its English name suggests.
- **Ballpoint has no Path A overlay style at all** — there is no `STROKE_STYLE_BALLPOINT`. It exists
  only as a software pen (type 8). Any ballpoint tool would have to render its live stroke as some
  other overlay style and only become a true ballpoint on repaint.
- BOOX also stores per-tool preferences rather than one global width — `NotePenInfo` keeps four
  `Map<Int, Float>` keyed by shape type: `penWithMap` [sic], `eraseWidthMap`,
  `penPressureSensitivityMap`, `penSmoothLevelMap`. That is a useful shape for a persisted tool model:
  **width, pressure sensitivity and smoothing are remembered per tool, and the eraser has its own
  width per tool too.**
- `PenArgs` is BOOX's per-tool record: `{ id: String, type: Int, width: Float, color: Int,
  attrs: PenAttrs, pressureSensitivityV2: Float?, smoothLevel: Float? }`, and `QuickPenList` holds
  the user's favourites row.

---

## Where Notesprout stands today

Purely factual, so a future session knows what already exists and what does not.

**Currently armed** (`OnyxNotebookView.kt`):

```kotlin
touchHelper
    .setStrokeWidth(3.0f)          // hardcoded, line 2564
    .setStrokeColor(penColorInt)   // the one thing that is user-controlled
// setStrokeStyle is never called → the overlay runs whatever style the firmware defaults to
```

Committed strokes repaint as a flat polyline: `Path` of `lineTo` segments through
`strokePaint` (`Style.STROKE`, `Cap.ROUND`, `Join.ROUND`, `strokeWidth = 3f`, anti-aliased), with only
the colour varying per stroke (`drawStrokePath`).

**Storage already has room, unused:**

| Slot | State |
|---|---|
| `StrokePoint.pressure: Float?`, `StrokePoint.tilt: Float?` | Present in the model, always `null` — "hardware capture is not implemented yet" |
| `StrokeCodec` `FLAG_PRESSURE = 0x01`, `FLAG_TILT = 0x02` | Reserved in the binary format; v1 writes `flags = 0`. The decoder already derives stride from flags, **so per-point pressure/tilt can be added without a blob version bump** |
| `notebook.width` / `notebook.strokeWidth` columns | Exist (`NotebookObject`) |
| A per-stroke *tool/style* field | **Does not exist** in any form — not in the columns, not in `StrokeData`, not in `StrokeCodec` |
| `LiveStroke` pressure/tilt preservation | Already implemented — `srcPoints` carries them through moves so a re-save cannot destroy them |

---

## Device findings — NoteAir5C, 2026-08-01

Measured with `debug/PenToolSpikeActivity` (see below). Every question this document originally
listed as unanswerable from the binaries came back **positive**, including the three I expected to
fail. Device under test:

```
model=NoteAir5C  impl=SDMDevice  colorType=1
maxPressure=4095.0  touch=20832x15624  dpi=350
```

### Overlay styles (path A) — all 9 render, all visually distinct

Written as nine strokes down one page, each style set by a bare `setStrokeStyle` with no restart:

| Style | Observed on panel |
|---|---|
| `0 PENCIL` | plain even line — **this is what production ships today** |
| `1 FOUNTAIN` | fluid, clearly pressure-responsive |
| `2 MARKER` | even width, thinnest of all nine |
| `3 NEO_BRUSH` | fluid and pressure-responsive, **much thicker** than style 1 |
| `4 CHARCOAL` | visibly textured, pressure-responsive |
| `5 DASH` | even-width **dashed** line |
| `6 CHARCOAL_V2` | textured, **much thicker** than style 4 |
| `7 SQUARE_PEN` | **45° chisel nib** — one diagonal thick, the other thin and faint, H ≈ V between them |
| `8 SOFT_ERASER` | draws a very faint mark; true erase behaviour **not tested** (needs crossing existing ink) |

- **Styles 5, 6 and 7 work**, despite sitting past where `EpdController`'s own constant list stops.
  That was the single biggest risk in this document and it is cleared. **There were no silent
  failures at all** on this device.
- **`SQUARE_PEN` is confirmed a 45° chisel**, matching `NeoSquarePen`'s own
  `brushShape=RECTANGLE, brushAngle=45°, brushRatio=10.0` defaults.
- **Style 1 behaves like its pen-SDK name, not its device-SDK name.** It is a thin fluid pen
  (fountain); the fat brush is style 3 `NEO_BRUSH`. When the two layers disagree, trust `TouchHelper`.

### `setStrokeStyle` needs no restart, and survives fast mode

Each style took effect **on the very next stroke** after a bare `setStrokeStyle` on a live session —
no `restartRawDrawing()`, no teardown, no `setLimitRect` dance. The two `PENCIL` strokes at either
end of the nine-style walk were indistinguishable, so nothing drifted across it.

The entire walk ran under `scope=HAND_WRITING_REPAINT_MODE` — the app-scope fast-mode pin production
applies for the whole pen session. **Fast mode does not suppress stroke style**, exactly as it does
not suppress colour.

### Pressure and tilt are real, and already arriving

| Stroke | Observed |
|---|---|
| deliberately light | `pressure=1.0..1067.0  distinct=701` |
| deliberately heavy | `pressure=80.0..4095.0  distinct=138` |
| ordinary writing | `pressure=216.0..3610.0  distinct=927` |

Full 1–4095 range reachable, hundreds of distinct values per stroke, and `getMaxTouchPressure()`
is accurate. Tilt moves too (`tiltX=-17..55`, `tiltY=-13..30` across the session). Two caveats:

- **Pressure saturates at the ceiling.** A firm stroke pins at 4095 and `distinct` collapses; the
  usable band for width modulation sits below maximum.
- **Sample density is speed-dependent and can be large.** Observed 114 points for a quick full-width
  stroke against 2946 for a slow one — a 26× spread. Slow, careful writing samples densely.

Also observed once: **a single continuous pen-down→pen-up produced two
`onRawDrawingTouchPointListReceived` callbacks.** One callback per stroke is not guaranteed.

### Software renderers (path B) — 13 of 13 work, and some beat our polyline

`libneopen_jni.so` loads and `createPen`/`destroy` round-trips inside a third-party app. Timings
below are one repaint of a single 2506-point stroke at width 8:

| Renderer | ms | vs production polyline |
|---|---:|---|
| Fountain (wrapper) | **1.1** | **6× faster** |
| Square (NeoPen 9) | 5.9 | faster |
| FountainV2 (NeoPen 6) | 6.2 | faster |
| **Polyline — production today** | **7.1** | baseline |
| Marker (wrapper) | 8.4 | ~same |
| Fountain (NeoPen 2) | 9.0 | ~same |
| Brush (wrapper) | 11.5 | 1.6× |
| Ballpoint (NeoPen 8) | 27.7 | 3.9× |
| Marker (NeoPen 3) | 35.3 | 5× |
| Charcoal (NeoPen 4) | 38.0 | 5.4× |
| Brush (NeoPen 1) | 41.3 | 5.8× |
| CharcoalV2 (NeoPen 5) | 104.8 | 15× |
| Pencil (NeoPen 7) | 175.8 | 25× |

**Several solvers are faster than the flat polyline we draw today** — the `NeoFountainPenWrapper`
one-call helper reduces the stroke to far fewer draw operations than a 2506-segment `Path`. The
useful conclusion is qualitative: **richer ink is not automatically more expensive**, and the
stamp-based pens (pencil, charcoal) are the expensive tail.

> ⚠️ **Do not treat these absolute numbers as a benchmark.** Each is a *single* measurement of a
> *cold* render. On G102 the same 9 strokes re-rendered immediately after dropped from 43.5 ms to
> 5.2 ms (polyline) and 39.4 ms to 6.3 ms (pencil) — roughly **7× warmup** on the first pass after
> any change. So first-render figures are dominated by JIT/allocation warmup, not by solver cost, and
> the ordering above is only trustworthy where the gaps are large (fountain-wrapper vs pencil).
> Anyone planning against this needs a proper repeated-run benchmark first.

### Two traps that cost real time here

**1. `ResManager.init(context)` is mandatory before any bitmap-backed pen.**

```kotlin
com.onyx.android.sdk.base.utils.ResManager.init(applicationContext)   // onyxsdk-baselite
```

`NeoPencilPen` decodes `pencil.png` from the SDK's own resources and the charcoal pens stamp
bitmaps; all of it resolves through `ResManager`'s `appContext` lateinit. **Nothing in `TouchHelper`
or `NeoPenNative` initializes it** — BOOX's Notes app does it at its own startup. The failure mode is
nasty: the pencil first renders as a **solid, grainless stroke with no error at all**, and only
throws `UninitializedPropertyAccessException` later, when something forces the pen to be rebuilt.

**2. Texture pens need large widths or their texture does not exist.**

At width 8 the pencil is solid black and the charcoal is a faint dotted hairline — the grain bitmap
is scaled to the stroke width, so at 8 px there is no room for texture. At width 32 both show proper
grain. This is what `NoteConstant.CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0` and
`BRUSH_STROKE_WIDTH_EXTRA_SCALE = 2.0` are for: BOOX multiplies the nominal width per pen kind before
rendering. Those multipliers are not cosmetic.

**Also confirmed:** the Paint-style reading in this document is correct — `FILL` for the ballpoint's
`PenPathResult` outline, `STROKE` for the per-point pens.

## Second device — G102 (BOOX Go 10.3 Gen 2), 2026-08-02

The flagship, and a genuinely different panel: monochrome where the NA5C is Kaleido.

```
model=Go103_2  impl=SDMDevice  colorType=0
maxPressure=4096.0  touch=12399x9299  dpi=350
neopen_jni OK   ResManager.init OK
```

- **All 9 overlay styles render correctly here too**, verified by a clean 9-stroke walk (one stroke
  per style, styles 0–8 in order). Including `DASH`, `CHARCOAL_V2` and `SQUARE_PEN`. **Two panels,
  two firmwares, zero silent failures** — the pessimism in the original survey was unwarranted.
- **All 13 software renderers ran without a single failure**, on a heavier input than the NA5C used
  (9 strokes, ~14,400 points, repainted per renderer).
- Pressure is equally healthy: every stroke reached 4095 with 400–570 distinct values.

Two device deltas worth carrying forward:

| | NA5C | G102 |
|---|---|---|
| `colorType` | `1` | `0` | ← *not* a boolean; see P2P |
| `getMaxTouchPressure()` | `4095.0` | **`4096.0`** | ← **differs between devices** |
| digitizer | 20832×15624 | 12399×9299 | |

**`maxTouchPressure` is not a constant.** It differs by one between these two devices, and every
`NeoPen` config and wrapper call takes it as a normalizer. Hardcoding 4095 would be quietly wrong on
the flagship — always read `EpdController.getMaxTouchPressure()`.

## Third device — G6 (BOOX Go 6 Gen II), 2026-08-03

A different size class: 6", 1072×1448, half the linear resolution of the two 10.3" panels.

```
model=Go6_2  impl=SDMDevice  colorType=0
maxPressure=4096.0  touch=7239x5359  dpi=350
neopen_jni OK   ResManager.init OK
```

**All 9 overlay styles render, and all 13 software renderers pass.** Three devices, three firmwares,
two size classes — **no silent style failure has been observed anywhere.** The concern this document
opened with, that styles past `EpdController`'s enum would be firmware-dependent, is not borne out on
any Tier-1 hardware tested.

### Tilt is *not* on a common scale across devices

This only became visible with a third device:

| Device | `tiltX` observed | `tiltY` observed |
|---|---|---|
| NA5C | `-43..55` | `-13..38` |
| G102 | `-60..2` | `-4..38` |
| **G6** | **`2251..3156`** | **`-179..2446`** |

Pressure is a clean 1–4095 everywhere, but **tilt on G6 is reported on a completely different scale**
— cause unconfirmed, plausibly raw digitizer units rather than degrees (the SDK does carry
`TouchPoint.size2Tilt`/`tilt2Size` converters for that kind of packing).

*This is not a "the tester tilted the pen more on the small device" artifact*: the span **within a
single G6 stroke** was 2625 units, where the other three devices' entire observed range across whole
sessions was about −60..55. A stylus tilts at most ~90° from vertical, so neither the magnitudes nor
the within-stroke variation can be an angle. MAX later came in at `−24..44`, in line with NA5C and
G102 — so **G6 is the outlier, not the rule.**

There is no `getMaxTilt()` anywhere in the SDK to normalize against. So while
`NeoPenConfig.tiltEnabled` / `tiltScale` are tempting, **any tilt-driven feature needs per-device
calibration**, not merely a per-device maximum the way pressure does. Treat tilt as unusable until
someone characterizes it per model.

### The one-call wrappers build a pen per invocation

`NeoPenUtils.computeStrokePoints` — which every `*Wrapper.drawStroke()` helper routes through —
does `Companion.create(config)` → `onPenDown/Move/Up` → `NeoPen.destroy()` **on every call**. It does
not leak, but it does construct and tear down a native pen per stroke, per repaint.

The **first** call is measurably expensive — the fountain wrapper's cold render of a full page cost
431 ms on G6 and 789 ms on MAX, against ~60 ms for the cached-pen renderers. Beyond that first call
the picture is muddy: on MAX it warmed back to 69.7 ms (in line with everything else), on G6 it
stayed at 413 ms. **Those measurements are too noisy to rank renderers by** — see the benchmark
warning above; they are single samples on a device that is also refreshing an e-ink panel.

**What is solid is the structural fact, not the numbers:** the wrappers rebuild a native pen every
time they are called. **Use them for one-off rendering; for repainting a page, hold a `NeoPen` in a
`NeoPenRender` and reuse it** — remembering that width is baked in at create time, so the cache has
to be invalidated when width changes.

## Fourth device — MAX (BOOX Note Max), 2026-08-03

The largest panel tested: 13.3", 2400×3200 at dpi 450, digitizer 27040×20280.

```
model=NoteMax  impl=SDMDevice  colorType=0
maxPressure=4095.0  touch=27040x20280  dpi=450
neopen_jni OK   ResManager.init OK
```

**All 9 overlay styles render, all 13 software renderers pass** — on the heaviest input of the whole
survey (9 strokes, ~31,600 points; the big digitizer samples far denser, 2655–4051 points per stroke
against ~1600 on G102).

## Fifth device — P2P (BOOX Palma2 Pro), 2026-08-03

Phone-sized: 824×1648 at density 300, i.e. **`sw439dp` — the narrowest device in the fleet.**

```
model=Palma2_Pro_C  impl=SDMDevice  colorType=1017
maxPressure=4096.0  touch=8319x4159  dpi=350
neopen_jni OK   ResManager.init OK
```

**All 9 overlay styles render, all 13 software renderers pass.**

**`colorType=1017` kills the boolean reading of that field.** After three devices reporting `0` and
one reporting `1` it looked like a colour/mono flag, and this document said so. P2P is a colour
device reporting `1017`. Whatever it encodes — panel model, most likely — **anything gating on
`colorType == 1` would misread this device.** Use it only as an opaque identifier.

## Five-device summary — the survey is complete

| | NA5C | G102 | G6 | MAX | P2P |
|---|---|---|---|---|---|
| Overlay styles | **9/9** | **9/9** | **9/9** | **9/9** | **9/9** |
| Software renderers | **13/13** | **13/13** | **13/13** | **13/13** | **13/13** |
| `colorType` | 1 | 0 | 0 | 0 | **1017** |
| `getMaxTouchPressure()` | 4095 | **4096** | **4096** | 4095 | **4096** |
| tilt scale | ±60 | ±60 | **thousands** | ±44 | ±30 |
| panel | 10.3" Kaleido | 10.3" mono | 6" mono | 13.3" mono | 6.1" colour |

**Onyx's implementation is consistent across their entire line.** Five devices, five firmwares, four
size classes from 6.1" to 13.3", two colour panels — **every stroke style renders and every software
pen works, with no silent failure anywhere.** The premise this document opened with, that styles past
`EpdController`'s constant list would be firmware-dependent gambles, is simply not borne out.

Three things vary per device and must be read at runtime, never assumed:

| Field | Variation | Consequence |
|---|---|---|
| `getMaxTouchPressure()` | 4095 or 4096 | the normalizer every `NeoPen` config takes |
| tilt scale | G6 ~100× the others | no `getMaxTilt()` exists; needs per-device calibration |
| `colorType` | `0`, `1`, `1017` | **not a boolean** — opaque identifier only |

### Still open

- **Timings need a real benchmark.** See the warning above: every figure recorded here is a single
  cold render, and warmup dominates. Nothing about relative solver cost should be planned against
  until someone runs repeated passes.
- **All Tier-1 devices are now swept** (plus NA5C, Tier 2). Nothing failed anywhere. Remaining gaps
  are the non-BOOX targets (Wacom Movink, Supernote) which do not use this SDK at all.
- **Tilt needs per-device characterization** before any tilt-driven feature — see the G6 numbers.
- **Full-page cost is unmeasured.** Single strokes were timed; a page of thousands of strokes
  repainting through the stamp pens was not. Our render model (committed-content `RenderNode` +
  neighbour prefetch) should amortize it, but that is an assumption.
- **`SOFT_ERASER` (style 8) behaviour** — it marks rather than doing nothing, but was never tested
  against existing ink. Low value: Notesprout has its own eraser.

## The harness

`app/src/debug/kotlin/com/notesprout/android/debug/PenToolSpikeActivity.kt` — debug source set, never
ships. Retained past its go/no-go, as the colour spike was, because it is the tool for re-running the
style sweep on each remaining Tier-1 device.

```
adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.PenToolSpikeActivity
```

Two independent cyclers: **Style** walks the 9 firmware overlay styles (path A), **Render** walks the
13 software renderers plus a `None` (path B). **Auto** advances the style on each pen-up, which is
how a full style walk runs without touching chrome. **Restamp** re-renders captured strokes with the
current pen — the way one piece of handwriting gets compared across all 13.

Three things learned about *running* it that are not obvious:

- **`Render: None` only works if you never touch the chrome.** Any button tap calls `releaseRender()`,
  which hands the panel back to the Android layer — and with nothing committed that layer is blank,
  so the overlay ink being compared vanishes. Hence `Auto`.
- **`screencap` cannot capture path A.** The firmware overlay is not in the Android framebuffer, so
  overlay-style results are eyes-only and must be reported by the tester. Path B *is* capturable,
  which is why the software sweep can be driven entirely over adb.
- **The controls need a narrow-screen mode.** P2P is `sw439dp`, about half the width these rows were
  laid out for, and the overflow was **silent** — `Report` and `Clear` sat off the right edge with
  nothing on screen to indicate it. The harness now drops the controls the protocol does not use when
  `widthPixels < 1000`. Worth remembering generally: measure chrome against P2P first, per `CLAUDE.md`.
- **Button coordinates shift between taps.** The controls are `WRAP_CONTENT` in horizontal rows, so a
  label changing length (`Render: Polyline (production today)` → `Render: Pencil (NeoPen 7)`) moves
  every button after it. Drive it by resolving live bounds from `uiautomator dump` per tap, not by
  cached coordinates.

## References

- [OnyxAndroidDemo — Onyx-Pen-SDK.md](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md) (stale; documents 1.2.1 and two stroke styles)
- [OnyxAndroidDemo repository](https://github.com/onyx-intl/OnyxAndroidDemo) — `ScribbleTouchHelperDemoActivity` etc.; no NeoPen sample
- [Maven: com.onyx.android.sdk » onyxsdk-pen](https://mvnrepository.com/artifact/com.onyx.android.sdk/onyxsdk-pen)
- BOOX artifact repository (version lists): `http://repo.boox.com/repository/maven-public/com/onyx/android/sdk/`
- [BOOX — What Makes BOOX Stand Out as a Powerful Note-Taker](https://shop.boox.com/blogs/news/what-makes-boox-a-powerful-note-taker) (5 brushes, 16 colours, 25 widths, 4096 pressure levels)
- [BOOX Stylus & Pen Guide](https://help.boox.com/hc/en-us/articles/9146157867668-BOOX-Stylus-Pen-Guide)
- Related internal docs: [`docs/drawing-engine.md`](drawing-engine.md) (EPD rules, render model),
  [`docs/design-system.md`](design-system.md) (the ink-only colour exception),
  [`docs/toolbar.md`](toolbar.md) (where a tool picker would live)
