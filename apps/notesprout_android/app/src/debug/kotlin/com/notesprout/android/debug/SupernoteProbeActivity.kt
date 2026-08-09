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
 *  - barrel-button lab (Phase 4): does the OS deliver the side button to a third-party app,
 *    from hover and from contact, and under which value of the OS-level side-button
 *    preference? The report lists every pen/stylus/button-ish key readable from the
 *    Settings provider (re-read on every focus gain — flip the OS setting and come back).
 *    The barrel line shows live decoded buttonState for hover vs contact plus a transition
 *    counter; MIRROR toggles the engine's candidate behaviour (button held → setEraser,
 *    released → setPen) so the approach is validated here before it returns to
 *    RattaNotebookView. Write with the button held: no-MIRROR shows what the firmware does
 *    natively; MIRROR shows whether our reconfigure beats the tip landing.
 *  - REG lab (Phase 8): measures the horizontal registration offset between the firmware's
 *    live ink (true to the physical tip) and the digitizer's MotionEvent stream (what the
 *    engine bakes — observed landing a few px LEFT). With REG on, each stylus stroke is
 *    also drawn app-side at raw MotionEvent coords in the engine's exact bake style
 *    (2.5 px black; pen armed at the engine's EMR 250) without clearing the overlay —
 *    both lines sit on the panel at once. Nudge X±/Y± until the twin hides under the
 *    firmware ink (overlay pixels freeze app updates, so alignment = disappearance); the
 *    label's offset is the number RattaNotebookView.REG_OFFSET_X_PX wants. COL cycles the
 *    four firmware colour codes to calibrate RattaInkMap's grey anchors.
 *
 * Launch:
 *   adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.SupernoteProbeActivity
 */
class SupernoteProbeActivity : AppCompatActivity() {

    private companion object {
        const val SWEEP_EMR = 300      // known-visible; near-zero EMR is an invisible sub-pixel line
        const val DELAY_MS = 2000L     // clear-matrix arming delay — outlives any input event
        val PEN_NAMES = mapOf(
            1 to "eraser-rnd", 3 to "eraser-rect",
            10 to "NEEDLE", 11 to "MARK", 15 to "CALLIGRAPHY", 16 to "INK",
        )
        // ── Phase 8 REG lab ──
        /** The engine's exact live-pen EMR: RattaNotebookView.emrSize(2.5f) = 250. */
        const val REG_EMR = 250
        /** The engine's exact baked-stroke width (RattaNotebookView.strokePaint). */
        const val REG_STROKE_WIDTH = 2.5f
        /** The four colour codes the firmware pen accepts, for the COL cycler. */
        val FW_COLORS = listOf(
            SupernoteInk.Color.BLACK to "BLACK",
            SupernoteInk.Color.DARK_GRAY to "DK-GRAY",
            SupernoteInk.Color.GRAY to "GRAY",
            SupernoteInk.Color.LIGHT_GRAY to "LT-GRAY",
        )
    }

    private lateinit var reportText: TextView
    private lateinit var touchText: TextView
    private lateinit var barrelText: TextView
    private lateinit var penLabel: TextView
    private lateinit var writeArea: WriteArea

    private var penCode = SupernoteInk.Pen.NEEDLE
    private var stripEnabled = false
    private var toasted = false
    private var lastMoveReport = 0L

    // ── Phase 8 REG lab state ────────────────────────────────────────────────
    /**
     * Registration lab: with REG on, every stylus stroke is ALSO drawn app-side at its raw
     * MotionEvent coords (2.5 px black polyline — the engine's exact bake) WITHOUT clearing
     * the firmware overlay, so panel shows firmware ink and app twin side by side. The
     * X−/X+/Y−/Y+ buttons nudge the twin in 1 px steps; because pixels under overlay ink
     * are frozen against app updates (Phase 3 law), the twin VANISHES under the firmware
     * line when the offset is nulled — read the number off the label at that point. That
     * offset is the MotionEvent→physical-tip delta RattaNotebookView.REG_OFFSET_X_PX wants.
     * REG also arms the pen at the engine's exact EMR (250), so weight/darkness of live vs
     * app-drawn ink is compared in the same glance.
     */
    private var regEnabled = false
    private var regOffX = 0
    private var regOffY = 0
    private val regStrokes = mutableListOf<MutableList<android.graphics.PointF>>()
    private lateinit var regLabel: TextView

    /** Colour cycler (COL button): index into [FW_COLORS], used by every setPen site. */
    private var fwColorIdx = 0
    private fun fwColor() = FW_COLORS[fwColorIdx].first
    private fun sweepEmr() = if (regEnabled) REG_EMR else SWEEP_EMR

    // ── Barrel-button lab state ──────────────────────────────────────────────
    /** BUTTON_STYLUS_PRIMARY is the M+ mapping of the side button; some stacks still
     *  report the pre-M BUTTON_SECONDARY. Treat either as "barrel". */
    private val barrelMask =
        MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
    private var mirrorEnabled = false
    private var barrelPressed = false
    private var barrelTransitions = 0
    private var lastHoverBtn = -1     // -1 = never seen
    private var lastContactBtn = -1

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

        barrelText = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(barrelText, lp())
        updateBarrelText()

        root.addView(buttonRow(
            "CLEAR" to { regStrokes.clear(); SupernoteInk.clearAll(); writeArea.invalidate() },
            "STRIP" to { stripEnabled = !stripEnabled; applyDisableAreas(); writeArea.invalidate() },
            "FULL FRAME" to { SupernoteInk.sendOneFullFrame(this) },
            "MIRROR" to { toggleMirror() },
        ))

        // ── Phase 8 REG lab row: toggle, colour cycler, twin-offset nudges ──
        regLabel = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val regRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        regRow.addView(button("REG") { toggleReg() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        regRow.addView(button("COL") { cycleColor() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        regRow.addView(button("X−") { nudgeReg(-1, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        regRow.addView(button("X+") { nudgeReg(1, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        regRow.addView(button("Y−") { nudgeReg(0, -1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        regRow.addView(button("Y+") { nudgeReg(0, 1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        regRow.addView(regLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(regRow, lp())
        updateRegLabel()

        // ── Phase 3 clear-matrix: which sequence removes ALREADY-PAINTED overlay ink with
        // NO input event near it? Every sequence fires DELAY_MS after the arming tap, so the
        // tap itself (an input event) is long gone. Write strokes first, tap one button,
        // lift hand fully away, watch the ink at T+2 s. See SUPERNOTE_SUPPORT_PLAN.md
        // Phase 3 findings (rounds 1–4: bare clearAll never reconciles the panel; app-side
        // repaint composites BELOW the overlay; screenRefresh(false,0) full-flickers).
        root.addView(buttonRow(
            "dCLR" to { delayed("clearAll") { SupernoteInk.clearAll() } },
            "dCLR+INV" to { delayed("clearAll+invalidate") {
                SupernoteInk.clearAll(); writeArea.invalidate()
            } },
            "dCLR+PEN" to { delayed("clearAll+claim+setPen") {
                SupernoteInk.clearAll()
                SupernoteInk.claimPen()
                SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
            } },
            "dCLR+DIS" to { delayed("clearAll+disable-roundtrip") {
                SupernoteInk.clearAll()
                val dm = resources.displayMetrics
                SupernoteInk.setFullScreenDisable(dm.widthPixels, dm.heightPixels)
                writeArea.postDelayed({ applyDisableAreas() }, 300)
            } },
        ))
        root.addView(buttonRow(
            "dDIS" to { delayed("disable-roundtrip only (no clear)") {
                val dm = resources.displayMetrics
                SupernoteInk.setFullScreenDisable(dm.widthPixels, dm.heightPixels)
                writeArea.postDelayed({ applyDisableAreas() }, 300)
            } },
            "dCLR+UI" to { delayed("clearAll+fullUiAuto off/on") {
                SupernoteInk.clearAll()
                SupernoteInk.enableFullUiAuto(this, false)
                writeArea.postDelayed({ SupernoteInk.enableFullUiAuto(this, true) }, 300)
            } },
            "dCLR+RF1" to { delayed("clearAll+screenRefresh(false,1)") {
                SupernoteInk.clearAll(); SupernoteInk.screenRefresh(this, false, 1)
            } },
            "dCLR+RF2" to { delayed("clearAll+screenRefresh(false,2)") {
                SupernoteInk.clearAll(); SupernoteInk.screenRefresh(this, false, 2)
            } },
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
        // Re-read the OS stylus prefs so flipping the side-button setting and coming back
        // shows the new value next to the barrel readout.
        if (hasFocus && ::writeArea.isInitialized) reportText.text = buildReport()
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
        SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
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
        SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
        updatePenLabel()
    }

    private fun updatePenLabel() {
        val name = PEN_NAMES[penCode] ?: "?"
        penLabel.text = "code $penCode ($name) emr=${sweepEmr()} col=${FW_COLORS[fwColorIdx].second}"
    }

    // ---------------------------------------------------------------- Phase 8 REG lab

    private fun toggleReg() {
        regEnabled = !regEnabled
        if (!regEnabled) regStrokes.clear()
        // Re-arm at the engine's exact EMR (REG on) or the sweep EMR (REG off).
        SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
        updatePenLabel()
        updateRegLabel()
        writeArea.invalidate()
    }

    private fun cycleColor() {
        fwColorIdx = (fwColorIdx + 1) % FW_COLORS.size
        SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
        updatePenLabel()
    }

    private fun nudgeReg(dx: Int, dy: Int) {
        regOffX += dx
        regOffY += dy
        updateRegLabel()
        writeArea.invalidate()
    }

    private fun updateRegLabel() {
        regLabel.text = if (regEnabled) {
            "reg %+d,%+d px n=%d".format(regOffX, regOffY, regStrokes.size)
        } else {
            "reg off"
        }
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

        for (line in stylusSettingsLines()) appendLine(line)
    }.trimEnd()

    // ---------------------------------------------------------------- barrel lab

    /**
     * Track the stylus button state from BOTH input streams (hover generic-motion events
     * and contact touch events) — the OS could deliver the button in one but not the
     * other, and the engine's candidate design arms the eraser from hover so the firmware
     * is reconfigured before the tip lands. Every barrel press/release transition is
     * counted, logged (grep SupernoteProbe), and — with MIRROR on — mirrored into the
     * firmware pen config exactly the way RattaNotebookView would do it.
     */
    private fun trackButtons(e: MotionEvent, contact: Boolean) {
        val b = e.buttonState
        if (contact) lastContactBtn = b else lastHoverBtn = b
        val pressed = (b and barrelMask) != 0 &&
            e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
        if (pressed != barrelPressed) {
            barrelPressed = pressed
            barrelTransitions++
            android.util.Log.i("SupernoteProbe",
                "barrel ${if (pressed) "PRESS" else "RELEASE"} btn=$b " +
                "src=${if (contact) "contact" else "hover"} " +
                "tool=${e.getToolType(0)} action=${MotionEvent.actionToString(e.actionMasked)}")
            if (mirrorEnabled) applyMirror()
        }
        updateBarrelText()
    }

    private fun applyMirror() {
        if (barrelPressed) SupernoteInk.setEraser(false, 750)
        else SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
    }

    private fun toggleMirror() {
        mirrorEnabled = !mirrorEnabled
        // Turning it off while the eraser is armed must hand the pen back.
        if (!mirrorEnabled && barrelPressed) SupernoteInk.setPen(penCode, sweepEmr(), fwColor())
        if (mirrorEnabled) applyMirror()
        updateBarrelText()
    }

    private fun updateBarrelText() {
        barrelText.text = "barrel: hover=${btnFlags(lastHoverBtn)} " +
            "contact=${btnFlags(lastContactBtn)} Δ=$barrelTransitions " +
            "mirror=${if (mirrorEnabled) "ON" else "off"}"
    }

    /** Decode a buttonState int into named flags, e.g. "32[S1]". -1 = never seen. */
    private fun btnFlags(b: Int): String {
        if (b < 0) return "—"
        if (b == 0) return "0"
        val names = mutableListOf<String>()
        if (b and MotionEvent.BUTTON_PRIMARY != 0) names.add("PRI")
        if (b and MotionEvent.BUTTON_SECONDARY != 0) names.add("SEC")
        if (b and MotionEvent.BUTTON_TERTIARY != 0) names.add("TER")
        if (b and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) names.add("S1")
        if (b and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) names.add("S2")
        return "$b[${names.joinToString("+")}]"
    }

    /**
     * Every pen/stylus/button-ish key readable from the Settings provider (all three
     * tables), plus the known Ratta keys read explicitly. `end_button_behavior` is the
     * OS-level side-button preference (=2 observed via adb on the Nomad); the open
     * question is whether its value gates what trackButtons sees.
     */
    private fun stylusSettingsLines(): List<String> {
        val out = mutableListOf<String>()
        for (key in listOf("end_button_behavior", "stylus_guesture")) {
            val v = runCatching {
                android.provider.Settings.System.getString(contentResolver, key)
            }.getOrElse { "read failed: ${it.message}" }
            out.add("sys/$key=$v")
        }
        val pattern = Regex("pen|stylus|button|lamy|eraser", RegexOption.IGNORE_CASE)
        val tables = listOf(
            "sys" to android.provider.Settings.System.CONTENT_URI,
            "glob" to android.provider.Settings.Global.CONTENT_URI,
            "sec" to android.provider.Settings.Secure.CONTENT_URI,
        )
        for ((label, uri) in tables) {
            runCatching {
                contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        if (!pattern.containsMatchIn(name)) continue
                        val line = "$label/$name=${c.getString(1)}"
                        if (out.none { it == line || it.startsWith("$label/$name=") }) out.add(line)
                    }
                }
            }.onFailure { out.add("$label scan failed: ${it.message}") }
        }
        return out
    }

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
        // The engine's exact bake paint (RattaNotebookView.strokePaint) — the twin must be
        // the same pixels the real bake would produce, or the comparison lies.
        private val regPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = REG_STROKE_WIDTH
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
            if (regEnabled) {
                // App-side twins at MotionEvent coords + the current nudge. The firmware
                // overlay composites ABOVE this — when the nudge nulls the offset the twin
                // disappears under the firmware line (overlay pixels are frozen against
                // app updates), which is the measurement signal.
                for (pts in regStrokes) {
                    if (pts.size < 2) continue
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x + regOffX, pts[0].y + regOffY)
                    for (i in 1 until pts.size) path.lineTo(pts[i].x + regOffX, pts[i].y + regOffY)
                    canvas.drawPath(path, regPaint)
                }
                canvas.drawText("REG: twin redraws on pen-up; nudge X/Y until it hides under the firmware ink",
                    dp(8f), height - dp(12f), labelPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> strokePoints = 1
                MotionEvent.ACTION_MOVE -> strokePoints += event.historySize + 1
            }
            if (regEnabled && event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> regStrokes.add(
                        mutableListOf(android.graphics.PointF(event.x, event.y)))
                    MotionEvent.ACTION_MOVE -> regStrokes.lastOrNull()?.let { pts ->
                        for (i in 0 until event.historySize) {
                            pts.add(android.graphics.PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                        }
                        pts.add(android.graphics.PointF(event.x, event.y))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Draw the twin only at pen-up: an invalidate per MOVE would race
                        // the firmware's live painting and flicker the EPD.
                        regStrokes.lastOrNull()?.add(android.graphics.PointF(event.x, event.y))
                        updateRegLabel()
                        invalidate()
                    }
                }
            }
            trackButtons(event, contact = true)
            reportTouch(event, strokePoints)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        // Hover stream (pen in EMR range, tip up): where the barrel press should first
        // appear if the OS delivers it — the engine wants to re-arm BEFORE the tip lands.
        override fun onHoverEvent(event: MotionEvent): Boolean {
            trackButtons(event, contact = false)
            return true
        }

        // Some stacks report button changes as ACTION_BUTTON_PRESS/RELEASE generic events
        // rather than a buttonState change on the hover stream — catch those too.
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            trackButtons(event, contact = false)
            return super.onGenericMotionEvent(event)
        }

        private fun dp(v: Int): Int = this@SupernoteProbeActivity.dp(v)
        private fun dp(v: Float): Float = this@SupernoteProbeActivity.dp(v.toInt()).toFloat()
    }

    /** Arm [action] to fire in [DELAY_MS] — hands off the panel so no input event coincides. */
    private fun delayed(name: String, action: () -> Unit) {
        Toast.makeText(this, "$name in ${DELAY_MS / 1000}s — hands off!", Toast.LENGTH_SHORT).show()
        writeArea.postDelayed({
            android.util.Log.i("SupernoteProbe", "delayed sequence fired: $name")
            action()
        }, DELAY_MS)
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
