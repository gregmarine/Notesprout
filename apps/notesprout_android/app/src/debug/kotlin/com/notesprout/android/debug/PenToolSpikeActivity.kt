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
 * and nothing else. Every captured stroke always repaints with the **currently selected** renderer,
 * so turning the Render dial re-renders the same handwriting all 13 ways — that comparison is the
 * point, and making it automatic is deliberate: an earlier build kept each stroke's capture-time pen
 * and needed a separate confirm tap, which silently produced a whole device run of mislabelled
 * timings when that tap was skipped.
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
         * Undocumented-style probe. `StrokeStyle` names 0–8, but `setStrokeStyle` is an unvalidated
         * pass-through to firmware — and 5/6/7 render on every device despite having no name at the
         * device layer, which is direct evidence the firmware's style space is wider than the SDK's
         * constant list. So 9–15 are untested, not absent.
         *
         * The baseline is interleaved between every probe **because that is what makes a negative
         * result readable**: an unsupported style leaves the firmware on whatever was set last, so a
         * probe that renders identically to the `0 PENCIL` before it was ignored, while anything that
         * looks different is a real tenth-or-later tool.
         */
        val PROBE_STYLES: List<Pair<String, Int>> = listOf(
            "0 base" to 0, "9 ?" to 9,
            "0 base" to 0, "10 ?" to 10,
            "0 base" to 0, "11 ?" to 11,
            "0 base" to 0, "12 ?" to 12,
            "0 base" to 0, "13 ?" to 13,
            "0 base" to 0, "14 ?" to 14,
            "0 base" to 0, "15 ?" to 15,
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
            // Type 10, BRUSH_SIGN — the one pen the SDK declares but ships no wrapper class for.
            // Reached by asking the native layer for a handle directly; see renderFor.
            PenSpec("BrushSign (NeoPen 10)", Kind.NEOPEN, type = 10, fill = false),
        )

        /**
         * The config playground: every `NeoPenConfig` field worth turning, with a value ladder each.
         *
         * Sliders are not an option on a panel this size, so a field cycler plus a value cycler
         * covers ~25 fields in two buttons. Each field keeps its own selection, so several can be
         * combined and the combination survives moving between them.
         *
         * **`scalePrecision` and `displayScale` come first deliberately.** They are the prime
         * suspects for the width flooding — point pens blacking out the whole canvas at width ≥ 12 —
         * and every other field is measured *through* them, so nothing further is trustworthy until
         * they are understood. `NeoPenConfig.Companion.getPrecision(f)` exists precisely to compute
         * the first one and this harness has never called it.
         */
        val TUNABLES: List<Tunable> = listOf(
            Tunable("scalePrecision", listOf(1f, 2f, 4f, 8f)) { c, v -> c.scalePrecision = v },
            Tunable("displayScale", listOf(0.5f, 1f, 2f, 4f)) { c, v ->
                c.displayScaleX = v; c.displayScaleY = v
            },
            Tunable("minWidth", listOf(0.001f, 0.5f, 1f, 2f, 4f)) { c, v -> c.minWidth = v },
            Tunable("pressureSens", listOf(0f, 0.15f, 0.3f, 0.375f, 0.5f, 0.6f, 1f)) { c, v ->
                c.pressureSensitivity = v
            },
            Tunable("velocitySens", listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) { c, v ->
                c.velocitySensitivity = v
            },
            Tunable("velocityAmp", listOf(0f, 0.5f, 1f, 2f)) { c, v -> c.velocityAmplifier = v },
            Tunable("smoothLevel", listOf(0f, 0.3f, 0.6f, 1f)) { c, v -> c.smoothLevel = v },
            Tunable("alphaFactor", listOf(0.25f, 0.5f, 0.75f, 1f)) { c, v -> c.alphaFactor = v },
            Tunable("brushSpacing", listOf(0.05f, 0.1f, 0.25f, 0.5f, 1f)) { c, v ->
                c.brushSpacing = v
            },
            // 0 circle · 1 ellipse · 2 rectangle
            Tunable("brushShape", listOf(0f, 1f, 2f)) { c, v -> c.brushShape = v.toInt() },
            Tunable("brushRatio", listOf(1f, 2f, 5f, 10f, 20f)) { c, v -> c.brushRatio = v },
            Tunable("brushAngle", listOf(0f, 30f, 45f, 60f, 90f)) { c, v -> c.brushAngle = v },
            Tunable("rotateAngle", listOf(0f, 45f, 90f)) { c, v -> c.rotateAngle = v.toInt() },
            Tunable("directionOn", listOf(0f, 1f)) { c, v -> c.directionEnabled = v > 0f },
            Tunable("tiltOn", listOf(0f, 1f)) { c, v -> c.tiltEnabled = v > 0f },
            Tunable("tiltScale", listOf(1f, 3f, 5f, 10f)) { c, v -> c.tiltScale = v },
            Tunable("fastMode", listOf(0f, 1f)) { c, v -> c.fastMode = v > 0f },
            Tunable("startPointLimit", listOf(0f, 0.5f, 1f)) { c, v -> c.startPointLimit = v },
            Tunable("startLengthLimit", listOf(0f, 0.5f, 1f)) { c, v -> c.startLengthLimit = v },
            Tunable("endVelocitySens", listOf(0f, 0.5f, 1f)) { c, v -> c.endVelocitySensitivity = v },
        )

        /** Paint-style override applied to path B. `null` = use each spec's own default. */
        val PAINT_MODES: List<Pair<String, Boolean?>> =
            listOf("Auto" to null, "STROKE" to false, "FILL" to true)
    }

    /** One tunable field: a name, the ladder of values to walk, and how to write it onto a config. */
    class Tunable(
        val name: String,
        val values: List<Float>,
        val apply: (NeoPenConfig, Float) -> Unit,
    )

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
    private lateinit var probeButton: AppCompatButton
    private lateinit var fieldButton: AppCompatButton
    private lateinit var valueButton: AppCompatButton
    private lateinit var widthButton: AppCompatButton
    private lateinit var renderButton: AppCompatButton
    private lateinit var paintButton: AppCompatButton
    private lateinit var scopeButton: AppCompatButton

    private var styleIndex = 0
    private var widthIndex = DEFAULT_WIDTH_INDEX
    private var renderIndex = 0
    private var paintIndex = 0
    private var scopeIndex = 0
    private var fieldIndex = 0
    /** field name → index into that field's ladder. Absent = leave the SDK default alone. */
    private val tuning = mutableMapOf<String, Int>()
    private var probeMode = false
    private var showLabels = true
    private var autoAdvance = false
    private var lastAction = "—"

    /** Environment probes, read once at startup. */
    private var envReport = "not read"
    private var nativeProbe = "not probed"
    private var resManagerProbe = "not probed"
    private var penTypeProbe = "not probed"

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
        penTypeProbe = probePenTypes()
        // What does the helper we never called actually produce? If getPrecision(1.0) returns
        // something far from the 1.0 default this harness has been using, that alone would explain
        // the width flooding.
        Slog.d(TAG) { "PRECISION " + listOf(0.5f, 1f, 2f, 4f).joinToString("  ") { s ->
            "getPrecision($s)=" + runCatching { NeoPenConfig.Companion.getPrecision(s) }
                .getOrElse { "ERR:" + it.javaClass.simpleName }
        } }
        Slog.d(TAG) { "PENTYPES $penTypeProbe" }
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
        // Buttons need breathing room or a near-miss lands on the neighbour.
        fun LinearLayout.add(v: View) = addView(
            v,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(6); topMargin = dp(4); bottomMargin = dp(4)
            },
        )

        // P2P is sw439dp — roughly half the width these controls were laid out for, and the
        // overflow was silent: Report and Clear simply sat off the right edge with nothing on screen
        // to say so. The standard protocol only ever touches Style/Auto/Render/Labels/Clear, so on a
        // narrow panel the rest are omitted rather than shrunk. They still exist and still update;
        // they are just not added to the view.
        val compact = resources.displayMetrics.widthPixels < 1000

        // Row 1 — path A, the live firmware overlay.
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            styleButton = button("Style") {
                styleIndex = (styleIndex + 1) % styles().size
                canvas.setOverlayStyle(currentStyle(), restart = false)
                lastAction = "style=${styleLabel()} no-restart"
                refreshStatus()
            }
            add(styleButton)
            add(button("⏮") {
                styleIndex = (styleIndex + styles().size - 1) % styles().size
                canvas.setOverlayStyle(currentStyle(), restart = false)
                lastAction = "style=${styleLabel()} no-restart"
                refreshStatus()
            })
            // Question 2: same style, but with the session torn down and rebuilt around it.
            if (!compact) add(button("+Restart") {
                canvas.setOverlayStyle(currentStyle(), restart = true)
                lastAction = "setStrokeStyle + restartRawDrawing"
                refreshStatus()
            })
            // Steps the style forward on each pen-up so a full style walk needs no chrome taps at
            // all. That matters more than convenience: touching chrome calls releaseRender(), which
            // hands the panel back to the Android layer — and under the "None" renderer that layer
            // is blank, so the overlay ink being compared vanishes. Not touching anything is the
            // only way the accumulated-page comparison survives.
            probeButton = button("Probe") {
                probeMode = !probeMode
                styleIndex = 0
                canvas.setOverlayStyle(currentStyle(), restart = false)
                lastAction = "probeMode=${if (probeMode) "on (9..15)" else "off (0..8)"}"
                refreshStatus()
            }
            add(probeButton)
            autoButton = button("Auto") {
                autoAdvance = !autoAdvance
                lastAction = "autoAdvance=${if (autoAdvance) "on" else "off"}"
                refreshStatus()
            }
            add(autoButton)
            widthButton = button("Width") {
                widthIndex = (widthIndex + 1) % WIDTHS.size
                canvas.setWidth(currentWidth())
                lastAction = "width=${currentWidth()}"
                refreshStatus()
            }
            if (!compact) add(widthButton)
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
            add(renderButton)
            add(button("⏮") {
                renderIndex = (renderIndex + RENDERERS.size - 1) % RENDERERS.size
                lastAction = "render=${RENDERERS[renderIndex].label}"
                canvas.invalidate()
                refreshStatus()
            })
            paintButton = button("Paint") {
                paintIndex = (paintIndex + 1) % PAINT_MODES.size
                lastAction = "paint=${PAINT_MODES[paintIndex].first}"
                canvas.invalidate()
                refreshStatus()
            }
            if (!compact) add(paintButton)
        }

        // Row 4 — the config playground. Two cyclers cover every tunable field.
        val row4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fieldButton = button("Field") {
                fieldIndex = (fieldIndex + 1) % TUNABLES.size
                lastAction = "field=${TUNABLES[fieldIndex].name}"
                refreshStatus()
            }
            add(fieldButton)
            valueButton = button("⏭") {
                val f = TUNABLES[fieldIndex]
                // Absent → start at the bottom of the ladder; then walk and wrap back to absent, so
                // "leave the SDK default alone" is always reachable rather than a one-way door.
                val cur = tuning[f.name]
                val next = if (cur == null) 0 else cur + 1
                if (next >= f.values.size) tuning.remove(f.name) else tuning[f.name] = next
                lastAction = "${f.name}=${tuningLabel(f)}"
                canvas.rebuildPens()
                refreshStatus()
            }
            add(valueButton)
            add(button("Reset cfg") {
                tuning.clear()
                lastAction = "config reset to SDK defaults"
                canvas.rebuildPens()
                refreshStatus()
            })
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
            if (!compact) add(scopeButton)
            add(button("Labels") {
                showLabels = !showLabels
                canvas.invalidate()
                lastAction = "labels=${if (showLabels) "on" else "off"}"
                refreshStatus()
            })
            if (!compact) add(button("Repaint") {
                canvas.forceRepaint()
                lastAction = "handwritingRepaint"
                refreshStatus()
            })
            if (!compact) add(button("Report") {
                dumpReport()
                lastAction = "report → logcat"
                refreshStatus()
            })
            add(button("Clear") {
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
            addView(row4)
        }
    }

    /** The armed style sequence — the documented 0–8 walk, or the 9–15 probe. */
    private fun styles(): List<Pair<String, Int>> = if (probeMode) PROBE_STYLES else OVERLAY_STYLES
    private fun currentStyle(): Int = styles()[styleIndex].second
    private fun styleLabel(): String = styles()[styleIndex].first
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

    /**
     * Which pen types does the native solver actually know?
     *
     * `NeoPenConfig` names 1–10, but `onyxsdk-penbrush` ships a wrapper class for only nine of them —
     * `NEOPEN_PEN_TYPE_BRUSH_SIGN = 10` has none, which is why the renderer sweep never covered it.
     * The native layer is the authority, so ask it directly: a non-zero handle from `createPen` means
     * the type exists regardless of whether a Kotlin wrapper does. Probing past 10 also tests whether
     * the native type space runs beyond the constant list, the same question the overlay probe asks
     * of firmware.
     *
     * Each type is logged *before* it is attempted: an unknown type could conceivably fault in native
     * code rather than return 0, and a SIGSEGV takes the process with it — no `runCatching` saves
     * that. If the app dies during startup, the last PENTYPE line names the culprit.
     */
    private fun probePenTypes(): String {
        val ok = mutableListOf<Int>()
        val no = mutableListOf<Int>()
        for (type in 1..15) {
            Slog.d(TAG) { "PENTYPE probing $type" }
            val good = runCatching {
                val cfg = com.onyx.android.sdk.pennative.PenConfig().apply {
                    this.type = type
                    width = 8f
                    color = Color.BLACK
                    maxTouchPressure = 4096f
                    dpi = 350f
                }
                val handle = com.onyx.android.sdk.pennative.NeoPenNative.createPen(type, cfg)
                if (handle != 0L) {
                    com.onyx.android.sdk.pennative.NeoPenNative.destroyPen(handle)
                    true
                } else false
            }.getOrElse { false }
            if (good) ok += type else no += type
        }
        return "nativePenTypes ok=$ok none=$no"
    }

    /** The ladder value armed for a field, or "default" when the SDK's own value is left in place. */
    private fun tuningLabel(f: Tunable): String =
        tuning[f.name]?.let { f.values[it].toString() } ?: "default"

    /** Only the fields actually overridden — the whole point is knowing what is not stock. */
    private fun tuningSummary(): String {
        val set = TUNABLES.filter { tuning.containsKey(it.name) }
        return if (set.isEmpty()) "all SDK defaults"
        else set.joinToString("  ") { "${it.name}=${tuningLabel(it)}" }
    }

    /** Write every armed override onto a freshly built config. */
    private fun applyTuning(c: NeoPenConfig) {
        for (f in TUNABLES) tuning[f.name]?.let { f.apply(c, f.values[it]) }
    }

    private fun refreshStatus() {
        styleButton.text = "Style: ${styleLabel()}"
        autoButton.text = if (autoAdvance) "Auto: ON" else "Auto: off"
        probeButton.text = if (probeMode) "Probe: 9-15" else "Probe: off"
        fieldButton.text = "Field: ${TUNABLES[fieldIndex].name}"
        valueButton.text = "Val: ${tuningLabel(TUNABLES[fieldIndex])}"
        widthButton.text = "W: ${currentWidth().toInt()}"
        renderButton.text = "Render: ${RENDERERS[renderIndex].label}"
        paintButton.text = "Paint: ${PAINT_MODES[paintIndex].first}"
        scopeButton.text = "Scope: ${scopeLabel()}"

        val text = buildString {
            append(envReport).append('\n')
            append(nativeProbe).append("  ").append(resManagerProbe).append('\n')
            append(penTypeProbe).append('\n')
            append("cfg: ").append(tuningSummary()).append('\n')
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

        private var overlayStyle = OVERLAY_STYLES[0].second   // 0 PENCIL either way
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
                    styleIndex = (styleIndex + 1) % styles().size
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

        /** Config changed — drop the cached native pens so the next repaint rebuilds them. */
        fun rebuildPens() {
            destroyRenderCache()
            invalidate()
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
                // Always the *currently selected* renderer, never the one a stroke happened to be
                // captured under. Anything else makes the Render dial silently cosmetic: it would
                // relabel the status line while still drawing the old pen, which is exactly the trap
                // that invalidated the first G102 run.
                val spec = RENDERERS[renderIndex]
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
            // The width dialled *now*, not the one the stroke happened to be captured under.
            // `stroke.width` records what path A drew it with and stays for the record; path B is a
            // re-render and must follow the current setting or the Width control does nothing here.
            val w = currentWidth()
            paint.strokeWidth = w
            paint.color = Color.BLACK

            val maxPressure = runCatching { EpdController.getMaxTouchPressure() }
                .getOrElse { 4096f }
                .let { if (it <= 0f) 4096f else it }

            when (spec.kind) {
                Kind.POLYLINE -> drawPolyline(c, stroke)
                // Unreachable in practice — onDraw returns before this when "None" is selected — but
                // the branch keeps the `when` exhaustive.
                Kind.NONE -> Unit

                // displayScale=1f — the spike draws 1:1, no zoom.
                Kind.W_FOUNTAIN -> NeoFountainPenWrapper.drawStroke(
                    c, paint, pts, 1f, w, maxPressure, false,
                )
                Kind.W_BRUSH -> NeoBrushPenWrapper.drawStroke(
                    c, paint, pts, w, maxPressure, false,
                )
                Kind.W_MARKER -> NeoMarkerPenWrapper.drawStroke(
                    c, paint, pts, w, false,
                )

                // render(canvas, paint, points) = onTouchPointList + render + reset, so one call
                // draws a whole committed stroke.
                // Keyed on the *currently selected* renderer. Keying on the stroke's capture-time
                // pen meant every NeoPen entry collided on one cache slot, so switching renderers
                // silently kept re-using whichever pen was built first — the renderers appeared to
                // work while only one of them ever ran.
                Kind.NEOPEN -> renderFor(renderIndex, spec, w, maxPressure)
                    .render(c, paint, pts)
            }
        }

        private fun drawPolyline(c: Canvas, stroke: Stroke) {
            val pts = stroke.points
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = currentWidth()
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            c.drawPath(path, paint)
        }

        private fun drawLabel(c: Canvas, stroke: Stroke, spec: PenSpec) {
            val p = stroke.points.firstOrNull() ?: return
            c.drawText("S${stroke.overlayStyle}·${RENDERERS[renderIndex].label}", p.x + 6f, p.y - 6f, labelPaint)
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
                // Last, so the playground can override anything the per-pen defaults set.
                applyTuning(this)
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
                // BRUSH_SIGN. `onyxsdk-penbrush` declares the type on NeoPenConfig and the native
                // solver accepts it (probed: createPen succeeds for 1..10), but there is no
                // NeoBrushSignPen class to call. So take the handle straight from the native layer
                // and wrap it in a concrete NeoNativePen subclass purely to interpret the
                // PenInkResult — NeoBrushPen, because BRUSH_SIGN is brush-family and that subclass
                // reads results as a PenPointResult of variable-size dabs.
                //
                // Its factory hardcodes type 1, so the handle-taking constructor is used instead.
                // That constructor's second parameter is Kotlin's synthetic DefaultConstructorMarker,
                // which cannot be named from Kotlin source — hence reflection rather than a direct
                // call. Experimental: the pairing of type 10 with NeoBrushPen's result reader is an
                // assumption, not something the SDK states.
                10 -> {
                    val handle = com.onyx.android.sdk.pennative.NeoPenNative
                        .createPen(10, config().toNativeConfig())
                    if (handle == 0L) null else {
                        val ctor = NeoBrushPen::class.java.getDeclaredConstructor(
                            java.lang.Long.TYPE,
                            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
                        ).apply { isAccessible = true }
                        NeoPenRender(ctor.newInstance(handle, null) as com.onyx.android.sdk.pen.NeoPen)
                    }
                }
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
