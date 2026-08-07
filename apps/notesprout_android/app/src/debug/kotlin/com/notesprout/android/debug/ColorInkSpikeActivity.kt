package com.notesprout.android.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Phase-0 colour-ink spike (colour-feature go/no-go). Debug source set only — never ships.
 *
 * Answers, on real hardware, the four questions the colour plan is blocked on:
 *
 *  1. Does [TouchHelper.setStrokeColor] produce coloured **live** ink on a Kaleido panel
 *     (NoteAir5C), or is the raw-drawing overlay black-only?
 *  2. Does a colour change take effect on a **live** session, or does it need
 *     [TouchHelper.restartRawDrawing] the way `setLimitRect` does?
 *  3. **Does the first-stroke fast-mode suppress colour?** Production pins the app into
 *     `UpdateMode.HAND_WRITING_REPAINT_MODE` via `applyAppScopeUpdate` for the whole pen session
 *     (see `OnyxNotebookView.openRawDrawing`). Fast handwriting waveforms are often 1-bit, which
 *     would force live ink black. The **Scope** button cycles that mode (including OFF) so the two
 *     can be compared stroke for stroke.
 *  4. What does `Device.currentDevice().getColorType()` report? (colour-device detection candidate)
 *
 * The committed canvas draws every stroke in the colour it was captured with, so a black overlay
 * over a coloured commit is immediately visible: the ink changes colour when the pen lifts.
 *
 * Launch:
 *   adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.ColorInkSpikeActivity
 * Results: on-screen status line + logcat tag "ColorInkSpike".
 */
class ColorInkSpikeActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ColorInkSpike"

        /** Matches production's tag so the spike exercises the same app-scope slot. */
        const val HWR_APP_SCOPE = "notesprout_hwr"

        /** Cycled by the "Scope" button. `null` = cleared (no app-scope pin). */
        val SCOPE_MODES: List<UpdateMode?> = listOf(
            UpdateMode.HAND_WRITING_REPAINT_MODE,   // what production pins today
            null,                                   // cleared — the control case
            UpdateMode.GCC,                         // colour-capable candidates
            UpdateMode.GC4,
            UpdateMode.DU,
            UpdateMode.GU_FAST,
            UpdateMode.REGAL,
        )

        /**
         * Probe set, ordered so each **pure** primary/secondary sits next to the **tuned** candidate
         * of the same hue. That pairing is the diagnostic: if pure green renders live but our
         * `#1E7A34` does not, the overlay has a saturation/lightness floor and the palette just needs
         * brighter colours. If neither green renders at any shade, the overlay cannot drive that CFA
         * channel at all and green is off the menu entirely.
         */
        val INKS: List<Pair<String, Int>> = listOf(
            "Black" to Color.BLACK,
            "R-pure" to Color.parseColor("#FF0000"),
            "R-ours" to Color.parseColor("#D0021B"),
            "G-pure" to Color.parseColor("#00FF00"),
            "G-ours" to Color.parseColor("#1E7A34"),
            "B-pure" to Color.parseColor("#0000FF"),
            "B-ours" to Color.parseColor("#1148C4"),
            "Cyan" to Color.parseColor("#00FFFF"),
            "Magenta" to Color.parseColor("#FF00FF"),
            "Yellow" to Color.parseColor("#FFFF00"),
            "Orange" to Color.parseColor("#E8590C"),
            "Amber" to Color.parseColor("#C08A00"),
            "Purple" to Color.parseColor("#7629B8"),
            "Gray" to Color.parseColor("#808080"),
        )
    }

    private lateinit var canvas: SpikeCanvas
    private lateinit var status: TextView
    private lateinit var controlBar: LinearLayout

    private lateinit var inkButton: AppCompatButton
    private lateinit var scopeButton: AppCompatButton

    private var inkIndex = 0
    private var scopeIndex = 0
    private var colorCuOn = false
    private var lastAction = "—"

    /** Read once at startup; the answer to question 4. */
    private var colorTypeReport = "not read"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        colorTypeReport = readColorType()
        Slog.d(TAG) { "DEVICE model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} $colorTypeReport" }

        canvas = SpikeCanvas(this)
        status = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
        }
        controlBar = buildControlBar()

        // Top guard: no tappable chrome against the top edge — on BOOX, reaching for it pulls the
        // status bar down instead of hitting the button. The canvas stays full-bleed (ink is welcome
        // in the guard band); only the control bar is pushed down. Extra clearance beyond the bare
        // status-bar height because the pull-down region overshoots it on this panel.
        val barTopMargin = TopGuard.heightPx(this) + dp(48)

        setContentView(FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(controlBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = barTopMargin
            })
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })

        // Keep the pen out of the control bar, exactly as production excludes the toolbar. The rect
        // runs from y=0 so the gap above the bar isn't a writable strip stranded under the chrome.
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildControlBar(): LinearLayout {
        fun button(label: String, onClick: () -> Unit) = AppCompatButton(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(56)
            minimumHeight = dp(56)
            setPadding(dp(14), dp(12), dp(14), dp(12))
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

        // Buttons need breathing room between them or a near-miss lands on the neighbour.
        fun LinearLayout.addSpaced(v: View) = addView(
            v,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8); topMargin = dp(6); bottomMargin = dp(6)
            },
        )

        // 14 probe colours won't fit as 14 buttons, so ink is a cycler that names its own state.
        // Each tap sets the colour WITHOUT a restart — that is also the question-2 case.
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            inkButton = button("Ink: Black") {
                inkIndex = (inkIndex + 1) % INKS.size
                val (name, argb) = INKS[inkIndex]
                canvas.setInk(argb, restart = false)
                lastAction = "ink=$name no-restart"
                refreshStatus()
            }
            addSpaced(inkButton)
            addSpaced(button("Ink ⏮") {
                inkIndex = (inkIndex + INKS.size - 1) % INKS.size
                val (name, argb) = INKS[inkIndex]
                canvas.setInk(argb, restart = false)
                lastAction = "ink=$name no-restart"
                refreshStatus()
            })
            addSpaced(button("+Restart") {
                canvas.setInk(canvas.inkColor, restart = true)
                lastAction = "setStrokeColor + restartRawDrawing"
                refreshStatus()
            })
            addSpaced(button("Repaint") {
                canvas.forceRepaint()
                lastAction = "handwritingRepaint"
                refreshStatus()
            })
            addSpaced(button("Clear") {
                canvas.clearStrokes()
                lastAction = "clear"
                refreshStatus()
            })
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            scopeButton = button("Scope: HAND_WRITING_REPAINT_MODE") {
                scopeIndex = (scopeIndex + 1) % SCOPE_MODES.size
                applyScope()
                lastAction = "appScope=${scopeLabel()}"
                refreshStatus()
            }
            addSpaced(scopeButton)
            addSpaced(button("ColorCU") {
                colorCuOn = !colorCuOn
                runCatching {
                    if (colorCuOn) EpdController.enableColorCU() else EpdController.disableColorCU()
                }.onFailure { Slog.d(TAG) { "colorCU toggle failed: $it" } }
                lastAction = "colorCU=${if (colorCuOn) "on" else "off"}"
                refreshStatus()
            })
            addSpaced(button("EpdColor") {
                // The other colour entry point: the static EpdController setter.
                runCatching { EpdController.setStrokeColor(canvas.inkColor) }
                    .onFailure { Slog.d(TAG) { "EpdController.setStrokeColor failed: $it" } }
                lastAction = "EpdController.setStrokeColor"
                refreshStatus()
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            addView(row1)
            addView(row2)
        }
    }

    private fun scopeLabel(): String = SCOPE_MODES[scopeIndex]?.name ?: "CLEARED"

    private fun applyScope() {
        val mode = SCOPE_MODES[scopeIndex]
        runCatching {
            if (mode == null) {
                EpdController.clearAppScopeUpdate()
            } else {
                EpdController.applyAppScopeUpdate(HWR_APP_SCOPE, true, false, mode, 0)
            }
        }.onFailure { Slog.d(TAG) { "applyScope(${scopeLabel()}) failed: $it" } }
        Slog.d(TAG) { "APP_SCOPE now=${scopeLabel()}" }
    }

    /** Question 4: the colour-device detection candidate. */
    private fun readColorType(): String = runCatching {
        val device = com.onyx.android.sdk.device.Device.currentDevice()
        "colorType=${device.colorType} impl=${device.javaClass.simpleName}"
    }.getOrElse { "colorType=UNAVAILABLE (${it.javaClass.simpleName}: ${it.message})" }

    private fun refreshStatus() {
        val (inkName, inkArgb) = INKS[inkIndex]
        val hex = String.format("#%06X", inkArgb and 0xFFFFFF)
        inkButton.text = "Ink: $inkName"
        scopeButton.text = "Scope: ${scopeLabel()}"
        val text = "model=${Build.MODEL}  $colorTypeReport\n" +
            "ink=$inkName $hex  (${inkIndex + 1}/${INKS.size})  appScope=${scopeLabel()}  " +
            "colorCU=${if (colorCuOn) "on" else "off"}  strokes=${canvas.strokeCount()}\n" +
            "last: $lastAction"
        status.text = text
        Slog.d(TAG) { "STATUS ${text.replace('\n', ' ')}" }
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
     * A minimal faithful copy of the production Onyx pen surface: raw drawing via [TouchHelper],
     * committed strokes drawn by [onDraw]. Each committed stroke keeps the ink colour that was
     * active when it was captured, so overlay colour and committed colour can be compared directly.
     */
    inner class SpikeCanvas(context: Context) : View(context) {

        private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, callback) }
        private var isSetup = false
        private var exclusion: Rect? = null

        /** Committed strokes: points + the ink colour captured with them. */
        private val strokes = mutableListOf<Pair<List<PointF>, Int>>()

        var inkColor: Int = Color.BLACK
            private set

        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f
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
                val pts = pointList.points?.map { PointF(it.x, it.y) } ?: return
                if (pts.isEmpty()) return
                strokes.add(pts to inkColor)
                Slog.d(TAG) { "STROKE captured points=${pts.size} ink=${String.format("#%06X", inkColor and 0xFFFFFF)}" }
                invalidate()
            }

            override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) = Unit
            override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) = Unit
            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit
            override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) = Unit
        }

        fun strokeCount() = strokes.size

        fun setExclusion(rect: Rect?) {
            exclusion = rect
            if (isSetup) applyLimitRect()
        }

        /**
         * Question 2: with [restart] false the SDK is asked to change ink mid-session; with it true
         * the session is restarted the way `setLimitRect` has to be.
         */
        fun setInk(argb: Int, restart: Boolean) {
            inkColor = argb
            if (!isSetup) return
            touchHelper.setStrokeColor(argb)
            if (restart) {
                applyLimitRect()
                touchHelper.restartRawDrawing()
                touchHelper.setRawDrawingEnabled(true)
            }
            Slog.d(TAG) { "SET_INK ${String.format("#%06X", argb and 0xFFFFFF)} restart=$restart" }
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
            releaseRender()
            invalidate()
            post {
                forceRepaint()
                if (isSetup) touchHelper.setRawDrawingRenderEnabled(true)
            }
        }

        fun release() {
            if (isSetup) {
                runCatching { touchHelper.closeRawDrawing() }
                isSetup = false
            }
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
            c.drawColor(Color.WHITE)
            for ((pts, argb) in strokes) {
                if (pts.size < 2) continue
                paint.color = argb
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                c.drawPath(path, paint)
            }
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
                    .setStrokeWidth(3.0f)
                    .setStrokeColor(inkColor)
                    .openRawDrawing()
                isSetup = true
            } else {
                applyLimitRect()
                touchHelper.restartRawDrawing()
            }
            touchHelper.setRawDrawingEnabled(true)
            EpdController.setUpdListSize(512)
            Slog.d(TAG) { "OPEN_RAW done ink=${String.format("#%06X", inkColor and 0xFFFFFF)}" }
        }
    }
}
