package com.notesprout.android.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.notesprout.android.core.TopGuard
import com.notesprout.android.notebook.ratta.SupernoteInk

/**
 * Supernote (Ratta) firmware probe — Phase 0 part 2 + Phase 1 of SUPERNOTE_SUPPORT_PLAN.md.
 * Debug source set only — never ships. The on-screen panel IS the report (EPD overlays can't
 * be screenshotted and the logcat ring buffer floods).
 *
 * What it answers, on real hardware:
 *  - runtime binder handshake: does "service_myservice" resolve, is it alive, what descriptor
 *  - eink service class + enableFullUiAuto(boolean) present via reflection
 *  - pen tool type (TOOL_TYPE_STYLUS is a hard requirement), pressure, tilt, buttonState,
 *    what the eraser end / barrel button report
 *  - live firmware ink under a real pen (write area; setup re-asserted on window focus)
 *  - the 0…31 pen-type sweep at a known-visible EMR (300) — hunting for a dashed/patterned
 *    style, which is what unlocks Phase 5's hardware lasso outline
 *  - disable areas: chrome is always excluded; STRIP toggles an extra test band (dashed
 *    outline) inside the write area — ink must stop at its boundary
 *
 * Launch:
 *   adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.SupernoteProbeActivity
 */
class SupernoteProbeActivity : AppCompatActivity() {

    private companion object {
        const val SWEEP_EMR = 300      // known-visible; near-zero EMR is an invisible sub-pixel line
        const val FIRMWARE_COLOR = SupernoteInk.Color.BLACK
        val PEN_NAMES = mapOf(
            1 to "eraser-rnd", 3 to "eraser-rect",
            10 to "NEEDLE", 11 to "MARK", 15 to "CALLIGRAPHY", 16 to "INK",
        )
    }

    private lateinit var reportText: TextView
    private lateinit var touchText: TextView
    private lateinit var penLabel: TextView
    private lateinit var writeArea: WriteArea

    private var penCode = SupernoteInk.Pen.NEEDLE
    private var stripEnabled = false
    private var toasted = false
    private var lastMoveReport = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Decision 2: firmware failures are loud. One toast per Activity instance.
        SupernoteInk.onFailure = { msg ->
            runOnUiThread {
                if (!toasted) {
                    toasted = true
                    Toast.makeText(this, "Firmware: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        reportText = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(reportText, lp())

        touchText = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = "touch: (none yet — touch the write area)"
        }
        root.addView(touchText, lp())

        root.addView(buttonRow(
            "CLEAR" to { SupernoteInk.clearAll(); writeArea.invalidate() },
            "STRIP" to { stripEnabled = !stripEnabled; applyDisableAreas(); writeArea.invalidate() },
            "FULL FRAME" to { SupernoteInk.sendOneFullFrame(this) },
        ))

        penLabel = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        val penRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        penRow.addView(button("PEN −") { changePen(penCode - 1) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        penRow.addView(penLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        penRow.addView(button("PEN +") { changePen(penCode + 1) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(penRow, lp())

        writeArea = WriteArea(this)
        root.addView(writeArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        TopGuard.applyInsetPadding(root)

        updatePenLabel()
        reportText.text = buildReport()
        // Location-dependent lines (write-area offset, disable areas) need layout first.
        writeArea.post {
            reportText.text = buildReport()
            applyDisableAreas()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The firmware hands the pen to other apps while we're away and resets full-UI ink;
        // re-assert the whole setup on every focus gain (attach alone is not enough).
        if (hasFocus) setupFirmware() else teardownFirmware()
    }

    override fun onDestroy() {
        SupernoteInk.clearAll()
        SupernoteInk.clearDisableAreas()
        SupernoteInk.enableFullUiAuto(this, false)
        SupernoteInk.onFailure = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- firmware

    private fun setupFirmware() {
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(this, true)
        SupernoteInk.enableAutoRegal(this, true)
        applyDisableAreas()
        SupernoteInk.setPen(penCode, SWEEP_EMR, FIRMWARE_COLOR)
    }

    private fun teardownFirmware() {
        SupernoteInk.clearAll()
        SupernoteInk.enableFullUiAuto(this, false)
    }

    /** Chrome (report + buttons above the write area) is always excluded; STRIP adds a test band. */
    private fun applyDisableAreas() {
        val rects = mutableListOf<Rect>()
        val loc = IntArray(2)
        writeArea.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        if (loc[1] > 0) rects.add(Rect(0, 0, screenW, loc[1]))
        if (stripEnabled) {
            val strip = stripRectInView()
            rects.add(Rect(strip).apply { offset(loc[0], loc[1]) })
        }
        if (rects.isEmpty()) SupernoteInk.clearDisableAreas()
        else SupernoteInk.setDisableAreas(rects)
    }

    /** The STRIP test band, in write-area view coordinates. */
    private fun stripRectInView(): Rect = Rect(0, dp(40), writeArea.width, dp(40) + dp(120))

    private fun changePen(code: Int) {
        penCode = code.coerceIn(0, 31)
        SupernoteInk.setPen(penCode, SWEEP_EMR, FIRMWARE_COLOR)
        updatePenLabel()
    }

    private fun updatePenLabel() {
        val name = PEN_NAMES[penCode] ?: "?"
        penLabel.text = "code $penCode ($name) emr=$SWEEP_EMR"
    }

    // ---------------------------------------------------------------- report

    @SuppressLint("PrivateApi")
    private fun buildReport(): String = buildString {
        appendLine("mfr=${Build.MANUFACTURER} model=${Build.MODEL}")
        appendLine("brand=${Build.BRAND} device=${Build.DEVICE} sdk=${Build.VERSION.SDK_INT}")

        val binders = try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getMethod("getService", String::class.java)
            listOf("service_myservice", "service.myservice").map { n ->
                n to (get.invoke(null, n) as? IBinder)
            }
        } catch (t: Throwable) {
            appendLine("ServiceManager reflection FAILED: ${t.message}")
            emptyList()
        }
        for ((name, b) in binders) {
            if (b == null) appendLine("$name: null")
            else appendLine("$name: alive=${b.isBinderAlive} " +
                "desc=\"${runCatching { b.interfaceDescriptor }.getOrDefault("?")}\"")
        }
        appendLine("SupernoteInk.isAvailable=${SupernoteInk.isAvailable()}")

        val eink = try { getSystemService("eink") } catch (t: Throwable) { null }
        if (eink == null) {
            appendLine("eink service: null")
        } else {
            val hasFullUi = runCatching {
                eink.javaClass.getMethod("enableFullUiAuto", Boolean::class.javaPrimitiveType)
            }.isSuccess
            appendLine("eink=${eink.javaClass.name} fullUiAuto=$hasFullUi")
        }

        val dm = resources.displayMetrics
        val loc = IntArray(2)
        writeArea.getLocationOnScreen(loc)
        appendLine("display=${dm.widthPixels}x${dm.heightPixels} dpi=${dm.densityDpi} " +
            "writeArea@(${loc[0]},${loc[1]})")
    }.trimEnd()

    // ---------------------------------------------------------------- touch readout

    private fun reportTouch(e: MotionEvent, points: Int) {
        val now = SystemClock.uptimeMillis()
        if (e.actionMasked == MotionEvent.ACTION_MOVE && now - lastMoveReport < 150) return
        lastMoveReport = now
        val tool = when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN(${e.getToolType(0)})"
        }
        val action = MotionEvent.actionToString(e.actionMasked)
        touchText.text = "tool=$tool $action\n" +
            "press=%.3f tilt=%.3f btn=%d pts=%d (%.0f,%.0f)".format(
                e.pressure, e.getAxisValue(MotionEvent.AXIS_TILT), e.buttonState,
                points, e.x, e.y)
    }

    // ---------------------------------------------------------------- write area

    /**
     * White surface the firmware paints on. The app draws nothing here except the STRIP
     * test band's dashed outline — live ink is entirely the firmware's, which is the point.
     * Captures MotionEvents to prove the point stream works (count shown in the readout).
     */
    private inner class WriteArea(context: Context) : View(context) {

        private var strokePoints = 0
        private val stripPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = dp(12).toFloat()
        }

        init { setBackgroundColor(Color.WHITE) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (stripEnabled) {
                val r = stripRectInView()
                canvas.drawRect(r, stripPaint)
                canvas.drawText("disable strip — ink must stop here",
                    r.left + dp(8f), r.top + dp(20f), labelPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> strokePoints = 1
                MotionEvent.ACTION_MOVE -> strokePoints += event.historySize + 1
            }
            reportTouch(event, strokePoints)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        private fun dp(v: Int): Int = this@SupernoteProbeActivity.dp(v)
        private fun dp(v: Float): Float = this@SupernoteProbeActivity.dp(v.toInt()).toFloat()
    }

    // ---------------------------------------------------------------- ui helpers

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(this).apply {
            text = label
            setTextColor(Color.BLACK)
            setOnClickListener { onClick() }
        }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(button(label, action), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return row
    }
}
