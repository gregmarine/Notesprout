package com.notesprout.android.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.notesprout.android.core.TopGuard
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.core.isRattaDevice
import com.notesprout.android.notebook.ratta.SupernoteInk
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Nib-indicator probe — can a small "current tool" badge be shown near the pen nib from the
 * HOVER stream, before the tip lands? Cross-device: the write area arms the real ink layer of
 * whichever device it runs on (Ratta firmware full-UI ink / BOOX TouchHelper raw drawing /
 * plain app polyline elsewhere), because the whole question is whether an app-side badge is
 * visible and harmless under REAL pen conditions, not on an idle panel.
 *
 * The UX friction it probes: with the toolbar hidden, the armed tool is invisible — the user
 * finds out it is wrong only by marking the page. A hover badge would answer before contact.
 *
 * What it measures, on real hardware (the on-screen panel IS the report):
 *  - hover delivery: ENTER/MOVE/EXIT counts + MOVE rate + AXIS_DISTANCE range, per source —
 *    view onHoverEvent vs onGenericMotionEvent vs (BOOX) the raw layer's onPenActive, which
 *    could keep firing when the view stream is starved by an armed raw-drawing session
 *  - the warning window: ENTER→DOWN lead ms (last + median) and last-hover→DOWN gap — is
 *    there even time to paint a badge before contact?
 *  - badge visibility: draws counted, invalidate→onDraw latency; on BOOX the open question
 *    is whether app invalidates inside the limit rect refresh AT ALL while raw drawing is
 *    enabled; on Ratta, overlay pixels freeze app updates, so a badge crossing already-inked
 *    regions is expected to misdraw (CLEAR resets the panel between runs)
 *  - clean exit: the hide repaint fires on DOWN/EXIT — on Ratta, app frames near pen-down
 *    are exactly where overlay law 2 eats clears, so watch whether the badge lingers under
 *    the first stroke
 *  - interference: with the badge following the nib, does the first stroke still land
 *    instantly (firmware/raw ink), or did hover-time invalidates cost anything?
 *
 * Controls: TOOL cycles the badge glyph (pen/eraser/lasso/highlight — cosmetic, nothing is
 * re-armed device-side); MODE cycles FOLLOW (badge tracks the nib, throttled partial
 * invalidates) → ENTER (drawn once where hover began, zero per-move churn — the cheapest
 * viable UX) → OFF (baseline: prove hover costs nothing when unused); POS cycles the badge
 * offset (above / up-left / up-right — which one the writing hand doesn't cover); INK
 * toggles the device ink layer; CLEAR wipes strokes + overlay (Ratta: clearAll+invalidate
 * in the same breath, overlay law 1).
 *
 * Launch:
 *   adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.NibProbeActivity
 */
class NibProbeActivity : AppCompatActivity() {

    private companion object {
        /** The engine's exact live-pen arming (RattaNotebookView): NEEDLE at EMR 250. */
        const val RATTA_EMR = 250
        /** FOLLOW-mode invalidate throttle — EPD churn budget, not a render limit. */
        const val FOLLOW_MIN_MS = 40L
        const val FOLLOW_MIN_PX = 8f
        /** Rolling window for the lead-time median. */
        const val LEAD_WINDOW = 32
    }

    private enum class Tool(val glyph: String, val label: String) {
        PEN("P", "pen"), ERASER("E", "eraser"), LASSO("L", "lasso"), HILITE("H", "highlight")
    }

    private enum class Mode { FOLLOW, ENTER, OFF }

    private enum class Pos(val label: String) { ABOVE("above"), UP_LEFT("up-left"), UP_RIGHT("up-right") }

    private lateinit var deviceText: TextView
    private lateinit var hoverText: TextView
    private lateinit var leadText: TextView
    private lateinit var badgeText: TextView
    private lateinit var writeArea: WriteArea

    private var tool = Tool.PEN
    private var mode = Mode.FOLLOW
    private var pos = Pos.UP_LEFT
    private var inkEnabled = true
    private var toasted = false

    private val onBoox = isBooxDevice()
    private val onRatta = isRattaDevice()

    // ── hover statistics ─────────────────────────────────────────────────────
    private var hoverEnters = 0
    private var hoverMoves = 0
    private var hoverExits = 0
    private var genericMoves = 0
    private var penActives = 0          // BOOX raw-layer hover signal
    private var moveRateHz = 0f
    private var rateWindowStart = 0L
    private var rateWindowCount = 0
    private var distMin = Float.MAX_VALUE
    private var distMax = -1f

    // ── lead-time statistics (the warning window) ────────────────────────────
    private var hoverEnterTime = 0L     // eventTime of the current hover session's ENTER
    private var lastHoverTime = 0L      // eventTime of the most recent hover event
    private var lastExitTime = 0L
    private var downs = 0
    private var lastEnterLead = -1L
    private var lastGap = -1L
    private var lastExitToDown = -1L
    private val enterLeads = ArrayDeque<Long>()

    // ── badge statistics ─────────────────────────────────────────────────────
    private var badgeDraws = 0
    private var hideDraws = 0
    private var lastDrawLatency = -1L
    private var lastStatsUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        deviceText = statusView(13f)
        hoverText = statusView(15f)
        leadText = statusView(15f)
        badgeText = statusView(15f)
        root.addView(deviceText, lp())
        root.addView(hoverText, lp())
        root.addView(leadText, lp())
        root.addView(badgeText, lp())

        root.addView(buttonRow(
            "TOOL" to {
                tool = Tool.entries[(tool.ordinal + 1) % Tool.entries.size]
                writeArea.invalidateBadge()
                updateStats(force = true)
            },
            "MODE" to {
                mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
                writeArea.invalidateBadge()
                updateStats(force = true)
            },
            "POS" to {
                pos = Pos.entries[(pos.ordinal + 1) % Pos.entries.size]
                writeArea.invalidateBadge()
                updateStats(force = true)
            },
            "INK" to { toggleInk() },
            "CLEAR" to { writeArea.clearPage() },
        ))

        writeArea = WriteArea(this)
        root.addView(writeArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        TopGuard.applyInsetPadding(root)

        deviceText.text = buildString {
            append("mfr=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            append(" · engine=")
            append(when {
                onRatta -> "RATTA firmware ink (avail=${SupernoteInk.isAvailable()})"
                onBoox -> "BOOX raw drawing"
                else -> "generic app polyline"
            })
        }
        updateStats(force = true)
        writeArea.post { if (onRatta && inkEnabled) rattaSetup() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::writeArea.isInitialized) return
        // Ratta hands the pen to other apps while we're away; BOOX raw sessions are also
        // re-asserted on focus (mirrors both production engines and both existing spikes).
        if (onRatta && inkEnabled) { if (hasFocus) rattaSetup() else rattaTeardown() }
        if (onBoox) writeArea.onFocusChangedBoox(hasFocus && inkEnabled)
    }

    override fun onDestroy() {
        if (onRatta) rattaTeardown().also { SupernoteInk.clearDisableAreas() }
        if (onBoox) {
            writeArea.releaseBoox()
            runCatching { EpdController.clearAppScopeUpdate() }
        }
        SupernoteInk.onFailure = null
        super.onDestroy()
    }

    // ── Ratta firmware arming (the probe's proven sequence, engine EMR) ─────

    private fun rattaSetup() {
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(this, true)
        SupernoteInk.enableAutoRegal(this, true)
        // Chrome above the write area stays firmware-ink-free, like the Supernote probe.
        val loc = IntArray(2)
        writeArea.getLocationOnScreen(loc)
        if (loc[1] > 0) {
            SupernoteInk.setDisableAreas(
                listOf(Rect(0, 0, resources.displayMetrics.widthPixels, loc[1]))
            )
        }
        SupernoteInk.setPen(SupernoteInk.Pen.NEEDLE, RATTA_EMR, SupernoteInk.Color.BLACK)
    }

    private fun rattaTeardown() {
        SupernoteInk.clearAll()
        SupernoteInk.enableFullUiAuto(this, false)
    }

    private fun toggleInk() {
        inkEnabled = !inkEnabled
        if (onRatta) { if (inkEnabled) rattaSetup() else rattaTeardown() }
        if (onBoox) writeArea.onFocusChangedBoox(inkEnabled)
        updateStats(force = true)
    }

    // ── statistics feed ─────────────────────────────────────────────────────

    /**
     * Every hover event from every source lands here. [dist] is AXIS_DISTANCE where the
     * stack reports it (EMR height above the glass) — its observed range tells us how much
     * physical approach warning exists at all.
     */
    private fun recordHover(action: Int, eventTime: Long, dist: Float, generic: Boolean) {
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER -> { hoverEnters++; hoverEnterTime = eventTime }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (generic) genericMoves++ else hoverMoves++
                // Same trick either way: ENTER can be swallowed while MOVEs still stream.
                if (hoverEnterTime == 0L) hoverEnterTime = eventTime
                rateWindowCount++
                val now = SystemClock.uptimeMillis()
                if (rateWindowStart == 0L) rateWindowStart = now
                else if (now - rateWindowStart >= 1000) {
                    moveRateHz = rateWindowCount * 1000f / (now - rateWindowStart)
                    rateWindowStart = now
                    rateWindowCount = 0
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> { hoverExits++; lastExitTime = eventTime }
        }
        lastHoverTime = eventTime
        if (dist >= 0f) {
            if (dist < distMin) distMin = dist
            if (dist > distMax) distMax = dist
        }
        updateStats()
    }

    private fun recordDown(eventTime: Long) {
        downs++
        if (hoverEnterTime != 0L) {
            lastEnterLead = eventTime - hoverEnterTime
            enterLeads.addLast(lastEnterLead)
            while (enterLeads.size > LEAD_WINDOW) enterLeads.removeFirst()
        }
        if (lastHoverTime != 0L) lastGap = eventTime - lastHoverTime
        if (lastExitTime != 0L) lastExitToDown = eventTime - lastExitTime
        hoverEnterTime = 0L
        updateStats(force = true)
    }

    private fun updateStats(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatsUpdate < 150) return
        lastStatsUpdate = now
        val dist = if (distMax < 0f) "—" else "%.2f..%.2f".format(distMin, distMax)
        hoverText.text = "hover: enter=$hoverEnters move=$hoverMoves gen=$genericMoves " +
            "exit=$hoverExits rate=%.0f/s dist=$dist penActive=$penActives".format(moveRateHz)
        val median = enterLeads.sorted().let { if (it.isEmpty()) -1L else it[it.size / 2] }
        leadText.text = "lead: enter→down=${fmtMs(lastEnterLead)} (med ${fmtMs(median)}) " +
            "lastHover→down=${fmtMs(lastGap)} exit→down=${fmtMs(lastExitToDown)} downs=$downs"
        badgeText.text = "badge: ${tool.label} mode=${mode.name.lowercase()} pos=${pos.label} " +
            "ink=${if (inkEnabled) "ON" else "off"} draws=$badgeDraws hides=$hideDraws " +
            "inv→draw=${fmtMs(lastDrawLatency)}"
    }

    private fun fmtMs(v: Long) = if (v < 0) "—" else "${v}ms"

    // ── write area ──────────────────────────────────────────────────────────

    /**
     * The probe surface. Ink is the device's real layer (see class doc); the badge is pure
     * app [onDraw] — a circle + tool glyph offset from the nib so the hand doesn't cover it,
     * plus a 3 px dot at the raw hover point to judge registration against the physical tip.
     */
    private inner class WriteArea(context: Context) : View(context) {

        // Badge geometry
        private val badgeRadius = dp(14).toFloat()
        private val badgeOffset = dp(44).toFloat()

        private var hoverPos: PointF? = null    // null = badge hidden
        private var badgeAnchor: PointF? = null // ENTER mode: frozen at hover-enter
        private var lastFollowInvalidate = 0L
        private var lastDrawnRect: Rect? = null
        private var pendingInvalidateAt = 0L

        // Generic-engine strokes (and BOOX committed strokes from the raw callback)
        private val strokes = mutableListOf<MutableList<PointF>>()

        private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f
        }

        init { setBackgroundColor(Color.WHITE) }

        // ── BOOX raw-drawing session (the spike's proven sequence) ──────────

        private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, rawCallback) }
        private var booxSetup = false

        private val rawCallback = object : RawInputCallback() {
            override fun onBeginRawDrawing(shortcut: Boolean, tp: TouchPoint) {
                if (booxSetup) touchHelper.setRawDrawingRenderEnabled(true)
            }
            override fun onEndRawDrawing(shortcut: Boolean, tp: TouchPoint) = Unit
            override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) = Unit
            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                val src = list.points ?: return
                if (src.isEmpty()) return
                strokes.add(src.mapTo(mutableListOf()) { PointF(it.x, it.y) })
                invalidate()
            }
            override fun onBeginRawErasing(shortcut: Boolean, tp: TouchPoint) = Unit
            override fun onEndRawErasing(shortcut: Boolean, tp: TouchPoint) = Unit
            override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) = Unit
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) = Unit
            // The raw layer's own pen-approach signal — does it fire during hover, and does
            // it keep firing when an armed raw session starves the view hover stream?
            override fun onPenActive(tp: TouchPoint) {
                penActives++
                post { recordHover(MotionEvent.ACTION_HOVER_MOVE, SystemClock.uptimeMillis(),
                    -1f, generic = false) }
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!onBoox) return
            viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (width <= 0 || height <= 0) return
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (inkEnabled) openRawBoox()
                }
            })
        }

        fun onFocusChangedBoox(active: Boolean) {
            if (!onBoox || width <= 0) return
            if (active) openRawBoox()
            else if (booxSetup) touchHelper.setRawDrawingEnabled(false)
        }

        fun releaseBoox() {
            if (booxSetup) {
                runCatching { touchHelper.closeRawDrawing() }
                booxSetup = false
            }
        }

        private fun applyLimitRectBoox() {
            val frame = Rect()
            getWindowVisibleDisplayFrame(frame)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val limitRect = Rect(
                maxOf(0, frame.left - loc[0]), maxOf(0, frame.top - loc[1]),
                minOf(width, frame.right - loc[0]), minOf(height, frame.bottom - loc[1]),
            )
            // Non-empty list required — the SDK treats emptyList() as a no-op.
            touchHelper.setLimitRect(limitRect, listOf(Rect(-1, -1, 0, 0)))
        }

        private fun openRawBoox() {
            if (!booxSetup) {
                applyLimitRectBoox()
                touchHelper
                    .setStrokeWidth(3f)
                    .setStrokeColor(Color.BLACK)
                    .openRawDrawing()
                booxSetup = true
            } else {
                applyLimitRectBoox()
                touchHelper.restartRawDrawing()
            }
            touchHelper.setRawDrawingEnabled(true)
            EpdController.setUpdListSize(512)
        }

        // ── page + badge management ─────────────────────────────────────────

        fun clearPage() {
            strokes.clear()
            if (onRatta) {
                // Overlay law 1: clearAll and the app frame in the same breath.
                SupernoteInk.clearAll()
                invalidate()
            } else if (onBoox && booxSetup) {
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
                post {
                    if (width > 0) EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                    touchHelper.setRawDrawingRenderEnabled(true)
                }
            } else {
                invalidate()
            }
        }

        /** Chrome toggled something badge-visible — repaint wherever the badge is/was. */
        fun invalidateBadge() {
            pendingInvalidateAt = SystemClock.uptimeMillis()
            invalidate()
        }

        private fun badgeCenter(nib: PointF): PointF = when (pos) {
            Pos.ABOVE -> PointF(nib.x, nib.y - badgeOffset)
            Pos.UP_LEFT -> PointF(nib.x - badgeOffset, nib.y - badgeOffset)
            Pos.UP_RIGHT -> PointF(nib.x + badgeOffset, nib.y - badgeOffset)
        }

        /** Badge + registration dot bounds around a nib position, padded for the outline. */
        private fun badgeRect(nib: PointF): Rect {
            val c = badgeCenter(nib)
            val pad = badgeRadius + dp(4)
            val r = Rect(
                (minOf(c.x, nib.x) - pad).toInt(), (minOf(c.y, nib.y) - pad).toInt(),
                (maxOf(c.x, nib.x) + pad).toInt(), (maxOf(c.y, nib.y) + pad).toInt(),
            )
            return r
        }

        private fun showBadgeAt(x: Float, y: Float, throttled: Boolean) {
            val prev = lastDrawnRect
            val now = SystemClock.uptimeMillis()
            val moved = hoverPos?.let {
                kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y)
            } ?: Float.MAX_VALUE
            hoverPos = PointF(x, y)
            if (throttled && now - lastFollowInvalidate < FOLLOW_MIN_MS && moved < FOLLOW_MIN_PX) return
            lastFollowInvalidate = now
            pendingInvalidateAt = now
            val next = badgeRect(PointF(x, y))
            if (prev != null) next.union(prev)
            invalidate(next)
        }

        private fun hideBadge() {
            val prev = lastDrawnRect ?: run { hoverPos = null; badgeAnchor = null; return }
            hoverPos = null
            badgeAnchor = null
            hideDraws++
            pendingInvalidateAt = SystemClock.uptimeMillis()
            invalidate(prev)
        }

        // ── input streams ───────────────────────────────────────────────────

        private fun isPen(e: MotionEvent) = e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER

        private fun handleHover(e: MotionEvent, generic: Boolean) {
            if (!isPen(e)) return
            recordHover(e.actionMasked, e.eventTime,
                e.getAxisValue(MotionEvent.AXIS_DISTANCE), generic)
            if (mode == Mode.OFF) return
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    if (mode == Mode.ENTER) {
                        // One draw where the approach began; static until down/exit.
                        if (badgeAnchor == null) {
                            badgeAnchor = PointF(e.x, e.y)
                            showBadgeAt(e.x, e.y, throttled = false)
                        }
                    } else {
                        showBadgeAt(e.x, e.y, throttled = true)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> hideBadge()
            }
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            handleHover(event, generic = false)
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            handleHover(event, generic = true)
            return super.onGenericMotionEvent(event)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (isPen(event)) {
                    recordDown(event.eventTime)
                    hideBadge()
                    if (!inkLayerLive()) strokes.add(mutableListOf(PointF(event.x, event.y)))
                }
                MotionEvent.ACTION_MOVE -> if (isPen(event) && !inkLayerLive()) {
                    strokes.lastOrNull()?.let { pts ->
                        for (i in 0 until event.historySize) {
                            pts.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                        }
                        pts.add(PointF(event.x, event.y))
                        invalidate()
                    }
                }
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        /** True when the device layer draws live ink itself, so the app must not double-draw. */
        private fun inkLayerLive() = inkEnabled && (onRatta || (onBoox && booxSetup))

        // ── drawing ─────────────────────────────────────────────────────────

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pendingInvalidateAt != 0L) {
                lastDrawLatency = SystemClock.uptimeMillis() - pendingInvalidateAt
                pendingInvalidateAt = 0L
            }
            // Committed strokes: app-baked on generic/BOOX (Ratta's live ink is overlay-only,
            // so with INK off there the polylines still show what the app captured).
            for (pts in strokes) {
                if (pts.size < 2) continue
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, strokePaint)
            }

            val nib = (if (mode == Mode.ENTER) badgeAnchor else hoverPos) ?: run {
                lastDrawnRect = null
                return
            }
            val c = badgeCenter(nib)
            canvas.drawCircle(c.x, c.y, badgeRadius, badgeFill)
            canvas.drawCircle(c.x, c.y, badgeRadius, badgeStroke)
            val baseline = c.y - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
            canvas.drawText(tool.glyph, c.x, baseline, glyphPaint)
            canvas.drawCircle(nib.x, nib.y, 3f, dotPaint)
            lastDrawnRect = badgeRect(nib)
            badgeDraws++
            updateStats()
        }
    }

    // ── ui helpers ──────────────────────────────────────────────────────────

    private fun statusView(sizeSp: Float) = TextView(this).apply {
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(AppCompatButton(this).apply {
                text = label
                setTextColor(Color.BLACK)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        return row
    }
}
