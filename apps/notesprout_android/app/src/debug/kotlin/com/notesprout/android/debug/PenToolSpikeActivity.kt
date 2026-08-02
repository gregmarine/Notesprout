package com.notesprout.android.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.notesprout.android.core.Slog
import com.notesprout.android.core.TopGuard
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.BallpointPenRenderWrapper
import com.onyx.android.sdk.pen.CharcoalNeoPenRender
import com.onyx.android.sdk.pen.NeoBallpointInkPen
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoCharcoalPenV2
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoFountainPenV2
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPen
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.NeoPencilPen
import com.onyx.android.sdk.pen.NeoSquarePen
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Pen-tool spike — the on-device half of [docs/onyx-pen-tools.md]. Debug source set only.
 *
 * That document surveyed the SDK from the decompiled AARs and ended with six questions the
 * binaries cannot answer. This harness answers them on real hardware.
 *
 *  1. **Which overlay stroke styles does this panel actually render?** `setStrokeStyle` is a
 *     verbatim pass-through to firmware through a reflected hidden method that swallows failures —
 *     an unsupported style produces no exception and no log. The only detector is your eyes, so the
 *     **Style** cycler walks all 9 constants and every committed stroke is labelled with the style
 *     it was written under.
 *  2. **Does a style change take on a live session, or does it need `restartRawDrawing()`** the way
 *     `setLimitRect` does? The **Style** button sets it without a restart; **+Restart** does both.
 *  3. **Does the first-stroke fast-mode app-scope suppress style?** Production pins
 *     `HAND_WRITING_REPAINT_MODE` for the whole pen session. Colour is known to survive that
 *     (see [ColorInkSpikeActivity]); style is untested. The **Scope** cycler compares them.
 *  4. **Is pressure/tilt actually populated on the points we receive?** `TouchPoint` carries
 *     `pressure`/`tiltX`/`tiltY` and production reads only x/y. The status line reports the observed
 *     range and distinct-value count per stroke — a constant pressure means every variable-width pen
 *     is decorative on this device.
 *  5. **Do the `NeoPen` software renderers work in a third-party app, and what do they cost?** The
 *     **Render** cycler swaps the committed-stroke renderer across all 13 candidates and the status
 *     line reports the wall-clock repaint time and the failure, if any.
 *  6. **Does firmware style 1 (`BRUSH` to the device layer / `FOUNTAIN` to the pen layer) behave
 *     like either name?** Write style 1 next to the software fountain and compare.
 *
 * The two halves are deliberately independent: **Style** drives the live firmware overlay (path A),
 * **Render** drives the software repaint (path B). Default Render is `Polyline`, exactly what
 * production draws today, so anything you see while writing that vanishes on pen-up is the overlay
 * and nothing else. **Restamp** re-renders every captured stroke with the current pen, which is how
 * you compare one piece of handwriting across all 13 renderers.
 *
 * Launch:
 *   adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.PenToolSpikeActivity
 * Results: on-screen status block + logcat tag "PenToolSpike" (`Report` dumps a full summary).
 */
class PenToolSpikeActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "PenToolSpike"

        /** Matches production's tag so the spike exercises the same app-scope slot. */
        const val HWR_APP_SCOPE = "notesprout_hwr"

        /**
         * Every stroke-style constant the SDK defines, including the three the device layer's own
         * enum stops short of (`DASH`/`CHARCOAL_V2`/`SQUARE_PEN`) and `SOFT_ERASER`, which exists
         * only on `StrokeStyle`. Values are passed to firmware verbatim; the names in parentheses
         * are what `EpdController` calls the same int, where it differs.
         */
        val OVERLAY_STYLES: List<Pair<String, Int>> = listOf(
            "0 PENCIL" to 0,
            "1 FOUNTAIN(=EpdBRUSH)" to 1,
            "2 MARKER" to 2,
            "3 NEO_BRUSH" to 3,
            "4 CHARCOAL" to 4,
            "5 DASH*" to 5,            // * = beyond EpdController's enum; support unknown
            "6 CHARCOAL_V2*" to 6,
            "7 SQUARE_PEN*" to 7,
            "8 SOFT_ERASER*" to 8,
        )

        /**
         * Chunky by default: at width 3 the styles are visually indistinguishable on e-ink, which
         * would read as "nothing works" when the truth is "nothing is visible".
         */
        val WIDTHS: List<Float> = listOf(3f, 5f, 8f, 12f, 20f, 32f)
        const val DEFAULT_WIDTH_INDEX = 2   // 8f

        /** Same ladder as the colour spike, so the two spikes' findings are comparable. */
        val SCOPE_MODES: List<UpdateMode?> = listOf(
            UpdateMode.HAND_WRITING_REPAINT_MODE,   // what production pins today
            null,                                   // cleared — the control case
            UpdateMode.GU_FAST,
            UpdateMode.GCC,
            UpdateMode.REGAL,
        )

        /**
         * The committed-stroke renderers, path B.
         *
         * `fill` is the Paint style each result type wants, read off `PenResult.draw`:
         * `PenPathResult` (ballpoint) is a solved **outline** and needs FILL; `PenPointResult`
         * (fountain/brush/marker/square) mutates `strokeWidth` per point and draws segments, so it
         * needs STROKE. It is a best reading of the bytecode, not a certainty — hence the **Paint**
         * override, which forces one style for everything so the guess can be falsified.
         */
        val RENDERERS: List<PenSpec> = listOf(
            PenSpec("Polyline (production today)", Kind.POLYLINE, fill = false),
            // Commits nothing and skips the white fill, so the firmware's own overlay ink is left
            // untouched on the panel and accumulates stroke after stroke. That is what makes the
            // path-A style walk a side-by-side comparison of a finished page rather than nine
            // separate did-it-change-when-I-lifted judgements. Kept next to Polyline so it is one
            // tap away from the baseline.
            PenSpec("None (leave overlay ink)", Kind.NONE, fill = false),
            PenSpec("Fountain (wrapper)", Kind.W_FOUNTAIN, fill = false),
            PenSpec("Brush (wrapper)", Kind.W_BRUSH, fill = false),
            PenSpec("Marker (wrapper)", Kind.W_MARKER, fill = false),
            PenSpec("Fountain (NeoPen 2)", Kind.NEOPEN, type = 2, fill = false),
            PenSpec("FountainV2 (NeoPen 6)", Kind.NEOPEN, type = 6, fill = false),
            PenSpec("Brush (NeoPen 1)", Kind.NEOPEN, type = 1, fill = false),
            PenSpec("Marker (NeoPen 3)", Kind.NEOPEN, type = 3, fill = false),
            PenSpec("Square (NeoPen 9)", Kind.NEOPEN, type = 9, fill = false),
            PenSpec("Ballpoint (NeoPen 8)", Kind.NEOPEN, type = 8, fill = true),
            PenSpec("Charcoal (NeoPen 4)", Kind.NEOPEN, type = 4, fill = true),
            PenSpec("CharcoalV2 (NeoPen 5)", Kind.NEOPEN, type = 5, fill = true),
            PenSpec("Pencil (NeoPen 7)", Kind.NEOPEN, type = 7, fill = true),
        )

        /** Paint-style override applied to path B. `null` = use each spec's own default. */
        val PAINT_MODES: List<Pair<String, Boolean?>> =
            listOf("Auto" to null, "STROKE" to false, "FILL" to true)
    }

    enum class Kind { POLYLINE, NONE, W_FOUNTAIN, W_BRUSH, W_MARKER, NEOPEN }

    data class PenSpec(
        val label: String,
        val kind: Kind,
        val type: Int = 0,
        val fill: Boolean = false,
    )

    /** One captured stroke plus the full tool state it was captured under. */
    class Stroke(
        val points: ArrayList<TouchPoint>,
        val overlayStyle: Int,
        val width: Float,
        var penIndex: Int,
    ) {
        val pressures: FloatArray = FloatArray(points.size) { points[it].pressure }
        val tiltX: IntArray = IntArray(points.size) { points[it].tiltX }
        val tiltY: IntArray = IntArray(points.size) { points[it].tiltY }
    }

    private lateinit var canvas: SpikeCanvas
    private lateinit var status: TextView
    private lateinit var controlBar: LinearLayout

    private lateinit var styleButton: AppCompatButton
    private lateinit var autoButton: AppCompatButton
    private lateinit var widthButton: AppCompatButton
    private lateinit var renderButton: AppCompatButton
    private lateinit var paintButton: AppCompatButton
    private lateinit var scopeButton: AppCompatButton

    private var styleIndex = 0
    private var widthIndex = DEFAULT_WIDTH_INDEX
    private var renderIndex = 0
    private var paintIndex = 0
    private var scopeIndex = 0
    private var showLabels = true
    private var autoAdvance = false
    private var lastAction = "—"

    /** Environment probes, read once at startup. */
    private var envReport = "not read"
    private var nativeProbe = "not probed"
    private var resManagerProbe = "not probed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // REQUIRED before any bitmap-backed pen. NeoPencilPen decodes `pencil.png` out of the SDK's
        // own resources and the charcoal pens stamp bitmaps too, all of which resolve through
        // ResManager's `appContext`. Nothing in TouchHelper or NeoPenNative initializes it — BOOX's
        // Notes app does it at its own startup — and without it the pencil silently renders as a
        // solid grainless stroke until something forces the pen to be rebuilt, at which point it
        // throws UninitializedPropertyAccessException instead.
        resManagerProbe = runCatching {
            com.onyx.android.sdk.base.utils.ResManager.init(applicationContext)
            "ResManager.init OK"
        }.getOrElse { "ResManager.init FAILED — ${it.javaClass.simpleName}: ${it.message}" }

        envReport = readEnv()
        nativeProbe = probeNativePen()
        Slog.d(TAG) { "ENV $envReport" }
        Slog.d(TAG) { "NATIVE $nativeProbe" }

        canvas = SpikeCanvas(this)
        status = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
        }
        controlBar = buildControlBar()

        // Top guard: no tappable chrome against the top edge — on BOOX, reaching for it pulls the
        // status bar down instead of hitting the button. Canvas stays full-bleed; only the bar moves.
        val barTopMargin = TopGuard.heightPx(this) + dp(48)

        setContentView(FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(controlBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = barTopMargin
            })
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })

        // Keep the pen out of the control bar, as production excludes the toolbar. From y=0 so the
        // gap above the bar isn't a writable strip stranded under the chrome.
        controlBar.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (controlBar.height <= 0) return
                    controlBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    canvas.setExclusion(Rect(0, 0, controlBar.right, controlBar.bottom))
                }
            }
        )

        applyScope()
        refreshStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildControlBar(): LinearLayout {
        fun button(label: String, onClick: () -> Unit) = AppCompatButton(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(Color.BLACK)
            // 1dp inkBlack border, 4dp radius — a visible target on e-ink, unlike white-on-white.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(maxOf(1, dp(1)), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

        // Buttons need breathing room or a near-miss lands on the neighbour.
        fun LinearLayout.addSpaced(v: View) = addView(
            v,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(6); topMargin = dp(4); bottomMargin = dp(4)
            },
        )

        // Row 1 — path A, the live firmware overlay.
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            styleButton = button("Style") {
                styleIndex = (styleIndex + 1) % OVERLAY_STYLES.size
                canvas.setOverlayStyle(currentStyle(), restart = false)
                lastAction = "style=${styleLabel()} no-restart"
                refreshStatus()
            }
            addSpaced(styleButton)
            addSpaced(button("⏮") {
                styleIndex = (styleIndex + OVERLAY_STYLES.size - 1) % OVERLAY_STYLES.size
                canvas.setOverlayStyle(currentStyle(), restart = false)
                lastAction = "style=${styleLabel()} no-restart"
                refreshStatus()
            })
            // Question 2: same style, but with the session torn down and rebuilt around it.
            addSpaced(button("+Restart") {
                canvas.setOverlayStyle(currentStyle(), restart = true)
                lastAction = "setStrokeStyle + restartRawDrawing"
                refreshStatus()
            })
            // Steps the style forward on each pen-up so a full style walk needs no chrome taps at
            // all. That matters more than convenience: touching chrome calls releaseRender(), which
            // hands the panel back to the Android layer — and under the "None" renderer that layer
            // is blank, so the overlay ink being compared vanishes. Not touching anything is the
            // only way the accumulated-page comparison survives.
            autoButton = button("Auto") {
                autoAdvance = !autoAdvance
                lastAction = "autoAdvance=${if (autoAdvance) "on" else "off"}"
                refreshStatus()
            }
            addSpaced(autoButton)
            widthButton = button("Width") {
                widthIndex = (widthIndex + 1) % WIDTHS.size
                canvas.setWidth(currentWidth())
                lastAction = "width=${currentWidth()}"
                refreshStatus()
            }
            addSpaced(widthButton)
        }

        // Row 2 — path B, the software repaint.
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            renderButton = button("Render") {
                renderIndex = (renderIndex + 1) % RENDERERS.size
                lastAction = "render=${RENDERERS[renderIndex].label}"
                canvas.invalidate()
                refreshStatus()
            }
            addSpaced(renderButton)
            addSpaced(button("⏮") {
                renderIndex = (renderIndex + RENDERERS.size - 1) % RENDERERS.size
                lastAction = "render=${RENDERERS[renderIndex].label}"
                canvas.invalidate()
                refreshStatus()
            })
            // The comparison that matters: same handwriting, every renderer in turn.
            addSpaced(button("Restamp") {
                canvas.restampAll(renderIndex)
                lastAction = "restamp all → ${RENDERERS[renderIndex].label}"
                canvas.invalidate()
                refreshStatus()
            })
            paintButton = button("Paint") {
                paintIndex = (paintIndex + 1) % PAINT_MODES.size
                lastAction = "paint=${PAINT_MODES[paintIndex].first}"
                canvas.invalidate()
                refreshStatus()
            }
            addSpaced(paintButton)
        }

        // Row 3 — environment and bookkeeping.
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            scopeButton = button("Scope") {
                scopeIndex = (scopeIndex + 1) % SCOPE_MODES.size
                applyScope()
                lastAction = "appScope=${scopeLabel()}"
                refreshStatus()
            }
            addSpaced(scopeButton)
            addSpaced(button("Labels") {
                showLabels = !showLabels
                canvas.invalidate()
                lastAction = "labels=${if (showLabels) "on" else "off"}"
                refreshStatus()
            })
            addSpaced(button("Repaint") {
                canvas.forceRepaint()
                lastAction = "handwritingRepaint"
                refreshStatus()
            })
            addSpaced(button("Report") {
                dumpReport()
                lastAction = "report → logcat"
                refreshStatus()
            })
            addSpaced(button("Clear") {
                canvas.clearStrokes()
                lastAction = "clear"
                refreshStatus()
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(6))
            addView(row1)
            addView(row2)
            addView(row3)
        }
    }

    private fun currentStyle(): Int = OVERLAY_STYLES[styleIndex].second
    private fun styleLabel(): String = OVERLAY_STYLES[styleIndex].first
    private fun currentWidth(): Float = WIDTHS[widthIndex]
    private fun scopeLabel(): String = SCOPE_MODES[scopeIndex]?.name ?: "CLEARED"

    private fun applyScope() {
        val mode = SCOPE_MODES[scopeIndex]
        runCatching {
            if (mode == null) EpdController.clearAppScopeUpdate()
            else EpdController.applyAppScopeUpdate(HWR_APP_SCOPE, true, false, mode, 0)
        }.onFailure { Slog.d(TAG) { "applyScope(${scopeLabel()}) failed: $it" } }
        Slog.d(TAG) { "APP_SCOPE now=${scopeLabel()}" }
    }

    /** Question 4's denominator, plus the device identity every finding has to be filed under. */
    private fun readEnv(): String = runCatching {
        val device = com.onyx.android.sdk.device.Device.currentDevice()
        val maxP = runCatching { EpdController.getMaxTouchPressure() }.getOrElse { -1f }
        val tw = runCatching { EpdController.getTouchWidth() }.getOrElse { -1f }
        val th = runCatching { EpdController.getTouchHeight() }.getOrElse { -1f }
        "model=${Build.MODEL} impl=${device.javaClass.simpleName} colorType=${device.colorType} " +
            "maxPressure=$maxP touch=${tw.toInt()}x${th.toInt()} dpi=${resources.displayMetrics.densityDpi}"
    }.getOrElse { "env UNAVAILABLE (${it.javaClass.simpleName}: ${it.message})" }

    /**
     * Question 5's precondition. `NeoPenNative` loads `libneopen_jni.so` in its static initializer;
     * if that library is missing or incompatible the whole software half is unavailable, and it is
     * far better to learn that here than from a blank canvas. Failure is an `UnsatisfiedLinkError`
     * — an `Error`, not an `Exception` — which is why this catches `Throwable` via `runCatching`.
     */
    private fun probeNativePen(): String = runCatching {
        val config = NeoPenConfig().apply { type = 2; width = 8f; maxTouchPressure = 4096f }
        val pen = NeoFountainPen.Companion.create(config)
            ?: return "neopen_jni LOADED but create() returned null"
        pen.destroy()
        "neopen_jni OK (createPen/destroy round-tripped)"
    }.getOrElse { "neopen_jni FAILED — ${it.javaClass.simpleName}: ${it.message}" }

    private fun refreshStatus() {
        styleButton.text = "Style: ${styleLabel()}"
        autoButton.text = if (autoAdvance) "Auto: ON" else "Auto: off"
        widthButton.text = "W: ${currentWidth().toInt()}"
        renderButton.text = "Render: ${RENDERERS[renderIndex].label}"
        paintButton.text = "Paint: ${PAINT_MODES[paintIndex].first}"
        scopeButton.text = "Scope: ${scopeLabel()}"

        val text = buildString {
            append(envReport).append('\n')
            append(nativeProbe).append("  ").append(resManagerProbe).append('\n')
            append("style=${styleLabel()}  width=${currentWidth()}  render=${RENDERERS[renderIndex].label}")
            append("  paint=${PAINT_MODES[paintIndex].first}  scope=${scopeLabel()}  strokes=${canvas.strokeCount()}\n")
            append("stroke: ").append(canvas.lastStrokeReport).append('\n')
            append("render: ").append(canvas.lastRenderReport).append('\n')
            append("last: ").append(lastAction)
        }
        status.text = text
        Slog.d(TAG) { "STATUS ${text.replace("\n", " | ")}" }
    }

    /** Everything a findings write-up needs, in one logcat block. */
    private fun dumpReport() {
        Slog.d(TAG) { "===== PEN TOOL SPIKE REPORT =====" }
        Slog.d(TAG) { envReport }
        Slog.d(TAG) { nativeProbe }
        Slog.d(TAG) { "scope=${scopeLabel()} paintMode=${PAINT_MODES[paintIndex].first}" }
        canvas.forEachStroke { i, s ->
            Slog.d(TAG) {
                "stroke[$i] style=${s.overlayStyle} width=${s.width} pen=${RENDERERS[s.penIndex].label} " +
                    "pts=${s.points.size} ${pressureSummary(s)}"
            }
        }
        Slog.d(TAG) { "renderErrors=${canvas.renderErrors}" }
        Slog.d(TAG) { "===== END REPORT =====" }
    }

    private fun pressureSummary(s: Stroke): String {
        if (s.points.isEmpty()) return "pressure=n/a"
        val distinct = s.pressures.toHashSet().size
        val txMin = s.tiltX.min(); val txMax = s.tiltX.max()
        val tyMin = s.tiltY.min(); val tyMax = s.tiltY.max()
        return "pressure=${s.pressures.min()}..${s.pressures.max()} distinct=$distinct " +
            "tiltX=$txMin..$txMax tiltY=$tyMin..$tyMax"
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Mirror production: release the EPD writing overlay on a touch in the chrome so button
        // state is visible immediately on e-ink.
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.y < controlBar.bottom && ::canvas.isInitialized
        ) {
            canvas.releaseRender()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        runCatching { EpdController.clearAppScopeUpdate() }
        canvas.release()
        super.onDestroy()
    }

    // ── Canvas ───────────────────────────────────────────────────────────────

    /**
     * A faithful copy of the production Onyx pen surface — raw drawing via [TouchHelper], committed
     * strokes drawn in [onDraw] — with the committed half swapped out for whichever path-B renderer
     * is armed. Full [TouchPoint]s are kept (not just x/y), because pressure and tilt are the input
     * every variable-width pen needs and are exactly what production throws away.
     */
    inner class SpikeCanvas(context: Context) : View(context) {

        private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, callback) }
        private var isSetup = false
        private var exclusion: Rect? = null

        private val strokes = mutableListOf<Stroke>()

        var lastStrokeReport = "—"
            private set
        var lastRenderReport = "—"
            private set
        var renderErrors = 0
            private set

        private var overlayStyle = OVERLAY_STYLES[0].second
        private var strokeWidth = WIDTHS[DEFAULT_WIDTH_INDEX]

        /**
         * One [NeoPenRender] per renderer index, built lazily. Native pens hold a handle and are
         * not free to construct, so they are reused across repaints — and cleared whenever the width
         * changes, since width is baked into the pen at create time.
         */
        private val renderCache = mutableMapOf<Int, NeoPenRender>()

        private val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val labelPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics,
            )
            typeface = android.graphics.Typeface.MONOSPACE
        }

        private val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
                if (isSetup) touchHelper.setRawDrawingRenderEnabled(true)
            }

            override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
                post { refreshStatus() }
            }

            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit

            override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
                val src = pointList.points ?: return
                if (src.isEmpty()) return
                // Copy: the SDK reuses/recycles the list it hands us.
                val copied = ArrayList<TouchPoint>(src.size)
                for (p in src) copied.add(TouchPoint(p))
                val stroke = Stroke(copied, overlayStyle, strokeWidth, renderIndex)
                strokes.add(stroke)
                lastStrokeReport = "pts=${copied.size} ${pressureSummary(stroke)}"
                Slog.d(TAG) { "STROKE style=$overlayStyle w=$strokeWidth $lastStrokeReport" }
                invalidate()
                if (autoAdvance) post {
                    styleIndex = (styleIndex + 1) % OVERLAY_STYLES.size
                    // Deliberately no restart and no chrome interaction — the next stroke reports
                    // whether a bare setStrokeStyle on a live session took effect.
                    setOverlayStyle(currentStyle(), restart = false)
                    lastAction = "auto → ${styleLabel()}"
                    refreshStatus()
                }
            }

            override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) = Unit
            override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) = Unit
            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit
            override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) = Unit
        }

        fun strokeCount() = strokes.size

        fun forEachStroke(block: (Int, Stroke) -> Unit) = strokes.forEachIndexed(block)

        fun restampAll(penIndex: Int) {
            strokes.forEach { it.penIndex = penIndex }
        }

        fun setExclusion(rect: Rect?) {
            exclusion = rect
            if (isSetup) applyLimitRect()
        }

        /** Questions 1 and 2 — the style change, with and without a session restart. */
        fun setOverlayStyle(style: Int, restart: Boolean) {
            overlayStyle = style
            if (!isSetup) return
            runCatching { touchHelper.setStrokeStyle(style) }
                .onFailure { Slog.d(TAG) { "setStrokeStyle($style) threw: $it" } }
            if (restart) {
                applyLimitRect()
                touchHelper.restartRawDrawing()
                touchHelper.setRawDrawingEnabled(true)
            }
            Slog.d(TAG) { "SET_STYLE $style restart=$restart" }
        }

        fun setWidth(w: Float) {
            strokeWidth = w
            destroyRenderCache()   // width is baked into a native pen at create time
            if (isSetup) touchHelper.setStrokeWidth(w)
            invalidate()
        }

        fun releaseRender() {
            if (!isSetup) return
            touchHelper.setRawDrawingRenderEnabled(false)
            invalidate()
        }

        fun forceRepaint() {
            if (width > 0 && height > 0) {
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            }
        }

        fun clearStrokes() {
            strokes.clear()
            renderErrors = 0
            lastStrokeReport = "—"
            lastRenderReport = "—"
            releaseRender()
            invalidate()
            post {
                forceRepaint()
                if (isSetup) touchHelper.setRawDrawingRenderEnabled(true)
            }
        }

        fun release() {
            destroyRenderCache()
            if (isSetup) {
                runCatching { touchHelper.closeRawDrawing() }
                isSetup = false
            }
        }

        private fun destroyRenderCache() {
            renderCache.values.forEach { runCatching { it.destroyPen() } }
            renderCache.clear()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (width <= 0 || height <= 0) return
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    openRaw()
                    post { applyLimitRect() }
                }
            })
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && width > 0 && height > 0) openRaw()
            else if (isSetup) touchHelper.setRawDrawingEnabled(false)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)

            // "None": paint nothing at all, not even the white ground — a white fill would be the
            // one thing capable of hiding the overlay ink we are trying to preserve. The panel then
            // shows pure firmware output. Labels are skipped too, so nothing composites over it;
            // strokes are identified by their order down the page instead.
            // ...except with nothing captured, where the white fill is how Clear wipes the page.
            if (RENDERERS[renderIndex].kind == Kind.NONE && strokes.isNotEmpty()) {
                lastRenderReport = "none — overlay ink left on panel (${strokes.size} strokes captured)"
                return
            }

            c.drawColor(Color.WHITE)
            if (strokes.isEmpty()) return

            val started = System.nanoTime()
            var errors = 0
            var lastError: String? = null

            for (stroke in strokes) {
                val spec = RENDERERS[stroke.penIndex]
                val result = runCatching { drawStroke(c, stroke, spec) }
                if (result.isFailure) {
                    errors++
                    val e = result.exceptionOrNull()
                    lastError = "${spec.label}: ${e?.javaClass?.simpleName}: ${e?.message}"
                    // Fall back to the baseline so a failing pen leaves evidence, not a blank page.
                    runCatching { drawPolyline(c, stroke) }
                }
                if (showLabels) drawLabel(c, stroke, spec)
            }

            val ms = (System.nanoTime() - started) / 1_000_000.0
            renderErrors = errors
            lastRenderReport = if (errors == 0) {
                "%d strokes in %.1fms (%.2fms/stroke)".format(
                    strokes.size, ms, ms / strokes.size,
                )
            } else {
                "%d strokes in %.1fms — %d FAILED · %s".format(strokes.size, ms, errors, lastError)
            }
        }

        /** Path B. Each branch is the exact call shape documented in `docs/onyx-pen-tools.md`. */
        private fun drawStroke(c: Canvas, stroke: Stroke, spec: PenSpec) {
            val pts = stroke.points
            if (pts.size < 2) return

            val fill = PAINT_MODES[paintIndex].second ?: spec.fill
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = stroke.width
            paint.color = Color.BLACK

            val maxPressure = runCatching { EpdController.getMaxTouchPressure() }
                .getOrElse { 4096f }
                .let { if (it <= 0f) 4096f else it }

            when (spec.kind) {
                Kind.POLYLINE -> drawPolyline(c, stroke)
                // Reachable only for a stroke *captured* under "None" that is now being repainted
                // under some other renderer — it stays unpainted until Restamp adopts it.
                Kind.NONE -> Unit

                // displayScale=1f — the spike draws 1:1, no zoom.
                Kind.W_FOUNTAIN -> NeoFountainPenWrapper.drawStroke(
                    c, paint, pts, 1f, stroke.width, maxPressure, false,
                )
                Kind.W_BRUSH -> NeoBrushPenWrapper.drawStroke(
                    c, paint, pts, stroke.width, maxPressure, false,
                )
                Kind.W_MARKER -> NeoMarkerPenWrapper.drawStroke(
                    c, paint, pts, stroke.width, false,
                )

                // render(canvas, paint, points) = onTouchPointList + render + reset, so one call
                // draws a whole committed stroke.
                Kind.NEOPEN -> renderFor(stroke.penIndex, spec, stroke.width, maxPressure)
                    .render(c, paint, pts)
            }
        }

        private fun drawPolyline(c: Canvas, stroke: Stroke) {
            val pts = stroke.points
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke.width
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            c.drawPath(path, paint)
        }

        private fun drawLabel(c: Canvas, stroke: Stroke, spec: PenSpec) {
            val p = stroke.points.firstOrNull() ?: return
            c.drawText("S${stroke.overlayStyle}·${spec.label}", p.x + 6f, p.y - 6f, labelPaint)
        }

        /**
         * Build (and cache) the renderer for a spec. Pencil and charcoal have dedicated
         * [NeoPenRender] subclasses; ballpoint has a one-call factory that makes pen and renderer
         * together; everything else drives a plain [NeoPenRender].
         */
        private fun renderFor(index: Int, spec: PenSpec, width: Float, maxPressure: Float): NeoPenRender {
            renderCache[index]?.let { return it }

            // Worth noting for any future implementation: every `create()` in this SDK is declared
            // **nullable** — the native `createPen` can fail and hand back nothing. A null here is a
            // real outcome to report, not a case to `!!` past.
            fun config(base: NeoPenConfig = NeoPenConfig()) = base.apply {
                type = spec.type
                this.width = width
                color = Color.BLACK
                this.maxTouchPressure = maxPressure
                dpi = resources.displayMetrics.densityDpi.toFloat()
            }

            val built: NeoPenRender? = when (spec.type) {
                1 -> NeoBrushPen.Companion.create(config())?.let { NeoPenRender(it) }
                2 -> NeoFountainPen.Companion.create(config())?.let { NeoPenRender(it) }
                3 -> NeoMarkerPen.Companion.create(config())?.let { NeoPenRender(it) }
                4 -> NeoCharcoalPen.Companion.create(config())?.let { CharcoalNeoPenRender(it) }
                5 -> NeoCharcoalPenV2.Companion.create(config())?.let { CharcoalNeoPenRender(it) }
                6 -> NeoFountainPenV2.Companion.create(config())?.let { NeoPenRender(it) }
                // Pencil/ballpoint/square start from the SDK's own tuned defaults — for pencil the
                // grain mask is generated from that config at create time — with our width and
                // pressure applied over the top.
                7 -> NeoPencilPen.Companion.create(config(NeoPencilPen.Companion.defaultPenConfig()))
                    ?.let { com.onyx.android.sdk.pen.PencilNeoPenRender(it) }
                8 -> BallpointPenRenderWrapper.Companion.create(
                    config(NeoBallpointInkPen.Companion.defaultPenConfig())
                )
                9 -> NeoSquarePen.Companion.create(config(NeoSquarePen.Companion.defaultPenConfig()))
                    ?.let { NeoPenRender(it) }
                else -> null
            }

            val render = built
                ?: error("create() returned null for NeoPen type ${spec.type} (${spec.label})")
            renderCache[index] = render
            return render
        }

        private fun applyLimitRect() {
            val frame = Rect()
            getWindowVisibleDisplayFrame(frame)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val limitRect = Rect(
                maxOf(0, frame.left - loc[0]),
                maxOf(0, frame.top - loc[1]),
                minOf(width, frame.right - loc[0]),
                minOf(height, frame.bottom - loc[1]),
            )
            // Non-empty list required — the SDK treats emptyList() as a no-op (see production).
            val excl = exclusion?.let { listOf(Rect(it)) } ?: listOf(Rect(-1, -1, 0, 0))
            touchHelper.setLimitRect(limitRect, excl)
        }

        private fun openRaw() {
            if (!isSetup) {
                applyLimitRect()
                touchHelper
                    .setStrokeWidth(strokeWidth)
                    .setStrokeColor(Color.BLACK)
                    .openRawDrawing()
                isSetup = true
                // After the session exists, so it lands on a live pipeline the way a picker would.
                runCatching { touchHelper.setStrokeStyle(overlayStyle) }
            } else {
                applyLimitRect()
                touchHelper.restartRawDrawing()
            }
            touchHelper.setRawDrawingEnabled(true)
            EpdController.setUpdListSize(512)
            Slog.d(TAG) { "OPEN_RAW style=$overlayStyle width=$strokeWidth" }
        }
    }
}
