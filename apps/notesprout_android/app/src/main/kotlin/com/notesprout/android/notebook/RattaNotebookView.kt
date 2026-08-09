package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.appcompat.content.res.AppCompatResources
import com.notesprout.android.R
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.util.Base64
import android.util.TypedValue
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import com.notesprout.android.core.ImageCodec
import com.notesprout.android.core.InkColor
import com.notesprout.android.core.Slog
import com.notesprout.android.core.markdown.TextObjectRenderer
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.translate
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.deepCopy
import com.notesprout.android.notebook.ratta.SupernoteInk
import java.io.ByteArrayOutputStream
import java.util.UUID

// Supernote (Ratta) engine — a SIBLING COPY of GenericNotebookView with the live-ink path
// replaced by the firmware's binder ink overlay (SUPERNOTE_SUPPORT_PLAN.md, Decision 1).
// The two files are slated to collapse into a shared CanvasNotebookView base (BACKLOG);
// until then A FIX TO EITHER FILE MUST BE APPLIED TO BOTH.
//
// What differs from GenericNotebookView (everything else is byte-identical):
//   • [firmware] gates every firmware call — with the binder absent the file behaves
//     exactly like Generic (per-move invalidate, in-progress stroke drawn in onDraw).
//   • Live stroke: the firmware paints it on the EPDC overlay at sub-frame latency, so
//     ACTION_MOVE only collects points (no invalidate) and onDraw skips the
//     in-progress-stroke block. Point capture stays on MotionEvent — the firmware
//     returns no point data.
//   • Deferred handoff: commitActiveStroke() adds the stroke to the model (saves,
//     snapshots, hit-tests and gesture gates stay correct immediately) but SKIPS the
//     redrawCanvas() bake; the overlay keeps showing the ink. releaseFirmwareOverlay()
//     — bake + SupernoteInk.clearAll() — runs only at natural boundaries (releaseRender
//     on toolbar touch, focus loss, page load/clear, template change, detach). Never
//     bake+clear per pen lift: that fights the hardware and produces a flash + ghost.
//   • Setup (attach + focus gain): claimPen, enableFullUiAuto(true), enableAutoRegal(true),
//     setPen(NEEDLE, emrSize(width), BLACK). NEEDLE = uniform width, matching our baked
//     polyline; the EMR clamp keeps the live ink visible (EMR ≈ 3 is sub-pixel/invisible).
//   • Per-tool firmware state (Phase 4): pen → setPen(NEEDLE); eraser tool → setEraser
//     (colour-255 payload — stops the firmware painting ink along the path; our software
//     hit-test still does the removal); text placement / shape transform / drag-move →
//     full-screen disable (their overlays are Canvas-drawn, so the firmware must paint
//     nothing). Every tool change is a handoff boundary: bake + clearAll FIRST,
//     then reconfigure. The toolbar/colour-panel exclusion rect arrives via
//     setToolbarExclusion in view coords and is offset into screen coords before being
//     sent as a firmware disable area. Colour mapping (live ink stays BLACK) is Phase 8.
//   • Hardware lasso trails (Phase 5): lasso / lasso-eraser modes arm the firmware's own
//     lasso pens (LASSO_DASH / LASSO_X — the sweep's codes 4/3) so the live trace is
//     painted at pen speed instead of chased by the 60 ms-throttled Canvas DashPathEffect.
//     MOVE still builds lassoGesturePath (the hit test needs the geometry) but skips the
//     Canvas overlay; lift clears the trace via the releaseGestureTrace ladder (the trace
//     corresponds to nothing in the app layer). Only the under-pen trails moved to
//     firmware — the post-lift selection box, drag preview and snap guides stay Canvas.
//     Drag-move must suppress from the HOVER stream, before the tip lands — the firmware
//     latches pen state at contact start (see updateLassoDragHoverSuppress).
//   • Lifecycle (Phase 6): firmware ink state is PROCESS-GLOBAL (pen claim, full-UI ink,
//     disable areas, overlay buffer). A static inkOwner guard (Onyx's penOwner mirror)
//     keeps an outgoing screen's late teardown — Android runs focus loss / detach /
//     destroy AFTER the successor's onResume — from wiping the successor's session.
//     Setup re-asserts from attach (post-layout, via onSizeChanged on first layout),
//     focus gain, and resumeDrawing (host onResume); enableDrawing/disableDrawing gate
//     input for dialogs and non-drawing views; releaseResources leaves nothing claimed.
//   • Barrel button: a held side button full-screen-disables the firmware (its native
//     x-stream button trace ignores the app's pen config but respects disable areas —
//     lab-measured; see updateBarrelSuppress), while the software erase does the work.
//   • Failures are loud (Decision 2): SupernoteInk logs at Log.w and this view toasts
//     once per instance via SupernoteInk.onFailure. No fallback, no engine swap.
class RattaNotebookView(context: Context) : View(context), NotebookView {

    companion object {
        private const val TAG = "RattaNotebookView"
        private const val ERASER_RADIUS_PX = 15f
        private const val ERASE_REDRAW_INTERVAL_MS = 60L
        private const val LASSO_REFRESH_INTERVAL_MS = 60L
        /** Floor/ceiling for the firmware EMR pen size — the Needle penSizeArray runs ~200…2400. */
        private const val EMR_MIN = 200
        private const val EMR_MAX = 1200
        /** Floor for the firmware eraser EMR size (PoC-validated: radius*50, min 400). */
        private const val ERASER_EMR_MIN = 400
        /**
         * EMR size for the firmware lasso trails (LASSO_DASH / LASSO_X) — the exact size the
         * Phase 0/1 sweep measured those codes rendering at. Kept independent of the ink pen's
         * width mapping: the trail is chrome, not ink.
         */
        private const val LASSO_TRAIL_EMR = 300
        /**
         * Follow-up overlay-clear ladder after a gesture-consumed stroke. A clear issued in
         * the wake of a pen-lift lands inside the ink daemon's stroke-finalization window
         * and is eaten, and that window's length varies by device and moment (a single
         * 450 ms attempt was reliable on the Manta but not the Nomad; the probe proved 2 s
         * always works on the Nomad). Each attempt is a clearAll + invalidate pair —
         * idempotent and invisible once the trace is gone — so retrying costs nothing.
         */
        private val GESTURE_TRACE_CLEAR_DELAYS_MS = longArrayOf(450L, 1000L, 1900L)

        /**
         * The view currently owning the process-global firmware ink state (pen claim,
         * full-UI ink, disable areas, overlay buffer) — the Ratta mirror of Onyx's
         * penOwner guard. Activity transitions run the incoming screen's onResume
         * (→ [setupFirmwareInk]) BEFORE the outgoing screen's onDestroy/detach, so an
         * unguarded late teardown would clearAll + full-screen-disable +
         * fullUiAuto(false) right over the successor's freshly-claimed session — the
         * "canvas goes dead after switching screens" bug Onyx already solved. Every
         * process-global teardown checks `inkOwner === this` first.
         */
        private var inkOwner: RattaNotebookView? = null
    }

    // ── Ratta firmware ink state ─────────────────────────────────────────────

    /** Whether the Supernote firmware ink daemon is reachable. Computed once, lazily. */
    private val firmware by lazy { SupernoteInk.isAvailable() }

    /**
     * True while finished strokes are shown by the firmware overlay but not yet baked into
     * [committedNode]. The strokes themselves are already in [strokes] (added on pen-up),
     * so this is purely a "the visual bake is deferred" flag — see [releaseFirmwareOverlay].
     */
    private var pendingBake = false

    /** One toast per view instance so a broken binder doesn't spam a writing session. */
    private var firmwareFailureToasted = false
    private val firmwareFailureHandler: (String) -> Unit = { msg ->
        post {
            if (!firmwareFailureToasted) {
                firmwareFailureToasted = true
                android.widget.Toast.makeText(context, "Supernote ink: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SupernoteInk.onFailure = firmwareFailureHandler
        // Before first layout width/height are 0 — a full-screen disable would be an empty
        // rect and getLocationOnScreen garbage. onSizeChanged runs the setup instead.
        if (width > 0 && height > 0) setupFirmwareInk()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The view stays attached across a task switch, so onAttachedToWindow won't re-run.
        // While we're away the firmware hands the pen to other apps and resets full-UI ink,
        // so a focus gain must re-assert the WHOLE setup, not just re-enable.
        if (hasWindowFocus) setupFirmwareInk() else teardownFirmwareInk()
    }

    override fun onDetachedFromWindow() {
        overlayClearArmed = false
        removeCallbacks(overlayClearRunnable)
        if (firmware && inkOwner === this) {
            releaseFirmwareOverlay()
            // Full-screen disable, not clearDisableAreas: between this view's death and the
            // next drawing surface's setup nothing should let the firmware paint stray ink.
            SupernoteInk.setFullScreenDisable(width, height)
            SupernoteInk.enableFullUiAuto(context, false)
            inkOwner = null   // also drops the static view ref — no Activity leak
        }
        if (SupernoteInk.onFailure === firmwareFailureHandler) SupernoteInk.onFailure = null
        super.onDetachedFromWindow()
    }

    /** (Re-)claim the firmware pen and turn on full-UI ink. Idempotent; safe to call often. */
    private fun setupFirmwareInk() {
        if (!firmware) return
        inkOwner = this   // process-global claim — a predecessor's late teardown now skips
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(context, true)
        SupernoteInk.enableAutoRegal(context, true)   // anti-ghosting waveform; keeps handoffs clean
        applyToolToFirmware()
    }

    private fun teardownFirmwareInk() {
        if (!firmware) return
        // A successor already set up (translucent scratch pad / sticky editor over us):
        // the firmware is theirs now — touching it would kill their live ink.
        if (inkOwner !== this) return
        releaseFirmwareOverlay()
        // "Drop the pen claim": the firmware has no unclaim transaction, so the enforceable
        // equivalent is a full-screen disable — while we are unfocused (dialog up, task
        // switched away) the firmware must not paint anywhere on our behalf. Focus gain
        // re-runs setupFirmwareInk, whose applyToolToFirmware restores the right areas.
        SupernoteInk.setFullScreenDisable(width, height)
        SupernoteInk.enableFullUiAuto(context, false)
    }

    /**
     * The handoff: bake any overlay-shown strokes into [committedNode], then clear the
     * firmware overlay so the app layer takes over. Called at natural boundaries ONLY —
     * never per pen lift (see the class header).
     */
    private fun releaseFirmwareOverlay() {
        if (!firmware) return
        if (pendingBake) {
            redrawCanvas()             // re-records from [strokes]; its guard clears the overlay
        } else {
            SupernoteInk.clearAll()    // nothing to bake — just wipe any overlay residue
        }
    }

    /**
     * Release the overlay after a gesture consumed its stroke (smart lasso / scribble /
     * shape dwell / erase contact) or a Phase 5 firmware lasso trail lifted. A gesture
     * stroke or trail is the one case where overlay ink corresponds to
     * NOTHING in the app layer, and Phase 3 device testing proved (both devices, three
     * rounds) that nothing app-side can remove its painted pixels:
     *   • a bare clearAll — even repeated 400 ms later — clears the buffer but the panel
     *     is never reconciled, so the trace stands until some later user action;
     *   • the overlay composites ABOVE the framebuffer — baking the stroke and then
     *     repainting those exact pixels black→white underneath moves nothing.
     * Only the EPDC can drop the painted pixels, so after the buffer clear we ask the eink
     * service for a partial (non-flashing) screen refresh — the reconcile that page turns
     * and toolbar taps were otherwise providing by accident.
     */
    private fun releaseGestureTrace() {
        if (!firmware) return
        // The immediate release is correct for the MODEL (bake the survivors now) but its
        // clearAll fires inside the daemon's stroke-finalization window and is usually
        // eaten — the ladder's clearAll + invalidate() pairs are what actually wipe the
        // trace (probe-measured: the wipe needs a co-presented app frame, and the clear
        // must be past the finalization window; hover/finger state irrelevant).
        releaseFirmwareOverlay()
        overlayClearArmed = true
        overlayClearAttempt = 0
        removeCallbacks(overlayClearRunnable)
        postDelayed(overlayClearRunnable, GESTURE_TRACE_CLEAR_DELAYS_MS[0])
    }

    private var overlayClearArmed = false
    private var overlayClearAttempt = 0

    private val overlayClearRunnable = object : Runnable {
        override fun run() {
            if (!firmware || !overlayClearArmed) return
            // Ownership moved to another drawing screen mid-ladder (fast navigation after
            // a lasso lift): a clearAll now would wipe THEIR live overlay ink. Stand down.
            if (inkOwner !== this@RattaNotebookView) { overlayClearArmed = false; return }
            // Never wipe live ink: mid-stroke → try again shortly; strokes pending bake →
            // the overlay is showing needed ink, leave it for the next natural boundary.
            if (penDown) { postDelayed(this, GESTURE_TRACE_CLEAR_DELAYS_MS[0]); return }
            if (pendingBake) { overlayClearArmed = false; return }
            SupernoteInk.clearAll()
            // The wipe only reaches the panel when an app frame is presented in the same
            // breath (probe matrix: dCLR+INV clears, bare dCLR never does). Present one.
            invalidate()
            overlayClearAttempt++
            if (overlayClearAttempt < GESTURE_TRACE_CLEAR_DELAYS_MS.size) {
                postDelayed(this, GESTURE_TRACE_CLEAR_DELAYS_MS[overlayClearAttempt] -
                    GESTURE_TRACE_CLEAR_DELAYS_MS[overlayClearAttempt - 1])
            } else {
                overlayClearArmed = false
            }
        }
    }

    /**
     * Extra armed-clear attempt at fresh pen contact — the one moment measured working in
     * every round (toolbar tap, tap-to-dismiss). Does NOT disarm the ladder: if this
     * attempt is eaten too, the timed retries still run (they self-disarm via pendingBake
     * once new ink commits).
     */
    private fun flushArmedOverlayClear() {
        if (!pendingBake) {
            SupernoteInk.clearAll()
            invalidate()
        }
    }

    /** NEEDLE (10) = uniform-width ballpoint, matching the uniform-width polyline we bake. */
    private fun applyPenToFirmware() {
        if (!firmware) return
        SupernoteInk.setPen(SupernoteInk.Pen.NEEDLE, emrSize(strokePaint.strokeWidth), SupernoteInk.Color.BLACK)
    }

    /**
     * px → firmware EMR size, from the hardware-validated PoC: floor(width*100), clamped to a
     * visible floor — an EMR near 0 paints an invisible sub-pixel line that reads as "no ink".
     */
    private fun emrSize(widthPx: Float): Int = (widthPx * 100f).toInt().coerceIn(EMR_MIN, EMR_MAX)

    /** Eraser-tool EMR size, from the PoC: radius*50 with a working floor. */
    private fun eraserEmr(): Int = (ERASER_RADIUS_PX * 50f).toInt().coerceAtLeast(ERASER_EMR_MIN)

    /**
     * True in the modes that own a FULL-SCREEN firmware disable — their visuals are entirely
     * Canvas-drawn, so the firmware must paint nothing anywhere. Lasso / lasso-eraser are NOT
     * in this set since Phase 5: their live trails are firmware ink (see [applyToolToFirmware]);
     * only a drag-move inside a selection suppresses (a drag must not leave a dashed trail).
     */
    private val firmwareSuppressed: Boolean
        get() = isTextPlacementMode || isShapeTransformMode || isDragMoveActive

    /** Toolbar/colour-panel exclusion rect, in VIEW coordinates, as pushed by the host. */
    private var toolbarExclusion: Rect? = null

    /**
     * Push the current tool state to the firmware — the per-mode half of every handoff.
     * Callers that change tool MUST release the overlay first (see [firmwareToolBoundary]).
     */
    private fun applyToolToFirmware() {
        if (!firmware) return
        barrelDown = false   // a tool push supersedes the transient barrel disable
        lassoHoverSuppressed = false   // ditto the hover-ahead drag suppress (re-asserted on next hover)
        if (firmwareSuppressed) {
            // Text placement / shape transform / drag-move: handles, placement tap and the
            // drag preview are all Canvas-drawn — no firmware ink anywhere.
            SupernoteInk.setFullScreenDisable(width, height)
            return
        }
        applyDisableAreas()
        when {
            // Phase 5: the firmware's own lasso vocabulary paints the live trail at pen
            // speed. BLACK payload = paints, never erases (eraser semantics are colour-255).
            isLassoMode       -> SupernoteInk.setPen(SupernoteInk.Pen.LASSO_DASH, LASSO_TRAIL_EMR, SupernoteInk.Color.BLACK)
            isLassoEraserMode -> SupernoteInk.setPen(SupernoteInk.Pen.LASSO_X,    LASSO_TRAIL_EMR, SupernoteInk.Color.BLACK)
            isEraserActive    -> {
                // Round eraser, colour-255 payload: the firmware stops painting ink along the
                // path (and natively wipes its own overlay pixels); our software hit-test
                // still does the actual stroke removal.
                SupernoteInk.setEraser(false, eraserEmr())
            }
            else              -> applyPenToFirmware()
        }
    }

    /**
     * Send the toolbar exclusion as a firmware disable area. ⚠️ Geometry differs from the
     * PoC: our toolbar OVERLAYS the drawing view inside a FrameLayout (same origin/size),
     * so the host's rect is in view coordinates and must be offset by getLocationOnScreen
     * into screen coordinates — the firmware's space. Null/empty ⇒ no disable areas.
     */
    private fun applyDisableAreas() {
        if (!firmware) return
        val excl = toolbarExclusion
        if (excl == null || excl.isEmpty) {
            SupernoteInk.clearDisableAreas()
            return
        }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        SupernoteInk.setDisableAreas(listOf(Rect(excl).apply { offset(loc[0], loc[1]) }))
    }

    /** Tool-change boundary: bake + clear the overlay FIRST, then push the new tool state. */
    private fun firmwareToolBoundary() {
        if (!firmware) return
        releaseFirmwareOverlay()
        applyToolToFirmware()
    }

    // ── Barrel-button erase (EMR pens with a side button) ────────────────────

    /**
     * Lab-measured on the Nomad (SupernoteProbeActivity barrel lab, 2026-08-09):
     *  • the side button arrives as BUTTON_STYLUS_PRIMARY on the HOVER stream, well
     *    before the tip lands (OS pref end_button_behavior=2);
     *  • while it is held the firmware paints its own lasso-erase x-stream trace along
     *    the pen path, IGNORING whatever pen/eraser config the app set (setEraser
     *    mirroring changed nothing) — but disable areas DO suppress it;
     *  • the trace lingers like any overlay ink.
     * So: press → full-screen disable (firmware paints nothing; the software erase in
     * [onTouchEvent]'s `erasing` path does the work and shows progress through its own
     * redraws); release → re-apply the armed tool. [applyToolToFirmware] resets
     * [barrelDown], so any tool push supersedes the transient disable and the next
     * button event re-asserts it if the button is still physically held.
     *
     * The physical ERASER END rides the same suppress (user-observed on both devices:
     * its native handling pixel-wipes the panel along the path, visible as a partial
     * erase across strokes until the software erase's redraw replaces it).
     */
    private var barrelDown = false
    /** True if the side button was seen at any point of the current pen contact. */
    private var strokeSawBarrel = false
    /** Whether the current contact began as an erase (eraser end / barrel / eraser tool). */
    private var strokeBeganErasing = false

    private fun updateBarrelSuppress(event: MotionEvent) {
        if (!firmware) return
        // The physical eraser end gets the same treatment as a held barrel button: its
        // native firmware handling wipes panel pixels along the path (a pixel-level erase
        // that flashes across strokes before our stroke-level erase repaints), so while it
        // is in EMR range — reported on the hover stream, ahead of contact, the only
        // moment a disable can beat the firmware's contact-start latch — the firmware
        // must paint (and wipe) nothing. The software erase shows the real progress.
        val pressed = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_STYLUS ->
                (event.buttonState and
                    (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
            else -> false
        }
        if (pressed && penDown) strokeSawBarrel = true
        if (firmwareSuppressed) return   // those modes already own a full-screen disable
        if (pressed == barrelDown) return
        barrelDown = pressed
        Slog.d(TAG) { "barrel ${if (pressed) "PRESS" else "RELEASE"} " +
            "src=${MotionEvent.actionToString(event.actionMasked)} " +
            "tool=${event.getToolType(0)} btn=${event.buttonState} penDown=$penDown" }
        if (pressed) SupernoteInk.setFullScreenDisable(width, height)
        else applyToolToFirmware()
    }

    /**
     * Hover-ahead suppress for the lasso drag-move (Phase 5, device-measured on both):
     * a full-screen disable issued at the drag's ACTION_DOWN is TOO LATE — the firmware
     * latches its pen state as the contact begins, so the whole drag still painted a
     * dashed trail. The disable must be in place before the tip lands, and the hover
     * stream is the early warning (the same channel that makes the barrel suppress work).
     * While the stylus hovers over the selection box: full-screen disable; hovering back
     * out re-arms the trail pen. [applyToolToFirmware] resets the flag (any tool push
     * supersedes the transient suppress; the next hover event re-asserts it).
     */
    private var lassoHoverSuppressed = false

    private fun updateLassoDragHoverSuppress(event: MotionEvent) {
        if (!firmware || !isLassoMode) return
        if (event.actionMasked != MotionEvent.ACTION_HOVER_ENTER &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE) return
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        if (firmwareSuppressed || barrelDown) return   // a stronger suppress already owns the firmware
        val box = lassoSelectionBox
        val inside = box != null && lassoSelectedIds.isNotEmpty() && box.contains(event.x, event.y)
        if (inside == lassoHoverSuppressed) return
        lassoHoverSuppressed = inside
        Slog.d(TAG) { "lasso hover ${if (inside) "SUPPRESS" else "REARM"} at ${event.x},${event.y}" }
        if (inside) SupernoteInk.setFullScreenDisable(width, height)
        else applyToolToFirmware()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        updateBarrelSuppress(event)
        updateLassoDragHoverSuppress(event)
        return super.onHoverEvent(event)
    }

    // Some stacks report button changes as ACTION_BUTTON_PRESS/RELEASE generic events
    // rather than a buttonState change on the hover stream — catch those too.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        updateBarrelSuppress(event)
        updateLassoDragHoverSuppress(event)
        return super.onGenericMotionEvent(event)
    }

    private val activePoints = ArrayList<PointF>()
    /**
     * Committed content (template + objects + strokes) as a hardware [RenderNode] — the retained GPU
     * layer, replacing the old software committed bitmap. Recorded by [redrawCanvas] on content
     * changes; blitted in [onDraw]. Mirrors OnyxNotebookView (minus the EPD handoffs). Off-screen
     * bitmaps (export, cover snapshot, drag backing) still build their own bitmaps.
     */
    private val committedNode = RenderNode("committed")
    private var isEraserActive = false

    private var lastEraseRedrawMs = 0L

    /** Template bitmap — drawn as the base layer behind all strokes. Null = white background. */
    private var templateBitmap: Bitmap? = null

    // Stroke store — LiveStroke carries the DB row UUID for incremental save / targeted erase.
    private val strokes = mutableListOf<LiveStroke>()

    // Heading store — populated from type="heading" rows at page load time.
    private var headings: List<HeadingStroke> = emptyList()

    // Text object store — populated from type="text" rows at page load time.
    private var textObjects: List<TextRender> = emptyList()

    // Line object store — populated from type="line" rows at page load time.
    private var lineObjects: List<LineRender> = emptyList()

    // Link object store — populated from type="link" rows at page load time.
    private var links: List<LinkRender> = emptyList()

    // Sticky note store — populated from type="sticky_note" rows at page load time.
    private var stickyNotes: List<StickyNoteRender> = emptyList()

    // Shape object store — populated from type="shape" rows at page load time.
    private var shapeObjects: List<ShapeRender> = emptyList()

    private val textObjectTextSizePx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
    )
    private val textObjectPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textObjectTextSizePx
    }

    // Link chrome — 1dp inkBlack outline/underline/chevron drawn around a link's union bbox.
    private val linkChromePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = resources.displayMetrics.density   // 1dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val linkChromeDashPaint = Paint(linkChromePaint).apply {
        val d = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
    }

    /**
     * Shared paint for every stroke draw. Its colour is **not** fixed — [drawStrokePath] sets it per
     * stroke from that stroke's own stored ink, and the in-progress stroke sets it from [penColorInt].
     * Anything drawing a stroke must go through one of those, or it inherits the last colour used.
     */
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f
    }

    /** Ink armed for new strokes — see [setPenColor]. Stored as hex; cached as an int for painting. */
    private var penColorHex: String = InkColor.DEFAULT
    private var penColorInt: Int = Color.BLACK

    override fun setPenColor(hex: String) {
        penColorHex = hex
        penColorInt = InkColor.paintColor(hex)
        // No live overlay here — the in-progress stroke is drawn by onDraw, which reads penColorInt.
        invalidate()
    }

    /**
     * Draw one stroke's polyline in **its own** stored ink. The single place stroke geometry becomes
     * pixels, so the per-stroke colour can never be forgotten at a call site.
     */
    private fun drawStrokePath(canvas: Canvas, stroke: LiveStroke) {
        val pts = stroke.points
        if (pts.size < 2) return
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        strokePaint.color = InkColor.paintColor(stroke.color)
        canvas.drawPath(path, strokePaint)
    }

    // ── Text placement mode ───────────────────────────────────────────────────

    private var isTextPlacementMode = false
    override var onTextPlacementTap: ((Float, Float) -> Unit)? = null

    // Coordinates captured on ACTION_DOWN; callback fires on ACTION_UP so the full
    // tap gesture is consumed by placement mode and not leaked to the drawing path.
    private var textPlacementTapX = 0f
    private var textPlacementTapY = 0f

    // ── Lasso state ──────────────────────────────────────────────────────────

    private var isLassoMode = false
    private var isLassoEraserMode = false
    private var lassoOverlayPath: Path? = null
    private var lassoSelectionBox: RectF? = null
    private var lassoGestureStartPoint: PointF? = null
    private var lassoGesturePath: Path? = null
    private var lassoGestureHadSelection = false
    private var lassoPreClearSelectionBox: RectF? = null
    private var lastLassoRefreshMs = 0L

    private var lassoEraserDisplayPath: Path? = null
    private val lassoEraserRandom = java.util.Random()
    private fun jitter() = (lassoEraserRandom.nextFloat() - 0.5f) * 8f

    // ── Lasso drag move state ────────────────────────────────────────────────

    private var isDragMoveActive = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragThresholdMet = false
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragOriginalStrokes: List<LiveStroke> = emptyList()
    private var dragOriginalHeadings: List<HeadingStroke> = emptyList()
    private var dragOriginalTextObjects: List<TextRender> = emptyList()
    private var dragOriginalLineObjects: List<LineRender> = emptyList()
    private var dragOriginalLinks: List<LinkRender> = emptyList()
    private var dragOriginalStickyNotes: List<StickyNoteRender> = emptyList()
    private var dragOriginalShapeObjects: List<ShapeRender> = emptyList()
    private var dragBackingBitmap: Bitmap? = null
    private var activeSnapGuides: List<SnapGuide> = emptyList()
    private var snapObjectTargets: List<RectF> = emptyList()
    override var isSnapEnabled: Boolean = false

    private val snapGuidePaint: Paint by lazy {
        val density = resources.displayMetrics.density
        Paint().apply {
            style       = Paint.Style.STROKE
            color       = Color.BLACK
            strokeWidth = density
            pathEffect  = DashPathEffect(floatArrayOf(12f * density, 6f * density), 0f)
            isAntiAlias = false
        }
    }

    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }

    private val lassoEraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 150, 150, 150)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }

    // ── NotebookView callbacks ────────────────────────────────────────────────

    override var onStrokeErased: ((String) -> Unit)? = null
    override var onHeadingErased: ((HeadingStroke) -> Unit)? = null
    override var onTextErased: ((TextRender) -> Unit)? = null
    override var onLineErased: ((LineRender) -> Unit)? = null
    override var onLinkErased: ((LinkRender) -> Unit)? = null
    override var onStickyNoteErased: ((StickyNoteRender) -> Unit)? = null
    override var onShapeErased: ((ShapeRender) -> Unit)? = null
    override var onShapeRecognized: ((LiveStroke, ShapeRecognizer.Result) -> Unit)? = null
    override var onShapeTransformed: ((ShapeRender, ShapeRender) -> Unit)? = null
    override var onShapeTransformTapOutside: (() -> Unit)? = null
    override var onShapeTransformDragStarted: (() -> Unit)? = null
    override var onShapeTransformMoved: ((android.graphics.RectF) -> Unit)? = null

    // ── Shape transform mode ─────────────────────────────────────────────────
    private var isShapeTransformMode = false
    private var transformBeforeRender: ShapeRender? = null
    private val transformController by lazy { ShapeTransformController(resources.displayMetrics.density) }
    override var onScribbleEraseComplete: ((List<String>, List<HeadingStroke>, List<TextRender>, List<LineRender>, List<LinkRender>, List<StickyNoteRender>, List<ShapeRender>) -> Unit)? = null
    override var onSmartLassoComplete: ((List<String>, RectF) -> Unit)? = null
    override var onPenLifted: (() -> Unit)? = null

    // Wall-clock time of the last ACTION_DOWN, used to compute gesture duration for smart-lasso velocity.
    private var strokeStartTimeMs = 0L

    // ── Pen-activity gate (see [NotebookView.isPenActive]) ───────────────────
    private var penDown       = false
    private var penLastLiftMs = 0L

    override val isPenActive: Boolean
        get() = penDown || (SystemClock.uptimeMillis() - penLastLiftMs) < PEN_ACTIVE_TAIL_MS

    // Dwell tracking: how long the stylus was still at the end of the stroke.
    private var dwellAnchorX   = 0f
    private var dwellAnchorY   = 0f
    private var lastMoveTimeMs = 0L
    private var dwellMs        = 0L
    override var onLassoComplete: ((Path, PointF) -> Unit)? = null
    override var onLassoTapToDismiss: (() -> Unit)? = null
    override var onLassoEraseComplete: ((List<String>) -> Unit)? = null
    override var lassoSelectedIds: Set<String> = emptySet()
    override var onStrokesMoved: ((List<LiveStroke>, List<LiveStroke>, List<HeadingStroke>, List<HeadingStroke>, List<TextRender>, List<TextRender>, List<LineRender>, List<LineRender>, List<LinkRender>, List<LinkRender>, List<StickyNoteRender>, List<StickyNoteRender>, List<ShapeRender>, List<ShapeRender>) -> Unit)? = null
    override var onLassoTap: ((Float, Float) -> Unit)? = null
    override var onDragStarted: (() -> Unit)? = null
    override var onLassoSelectionCleared: (() -> Unit)? = null

    // ── Touch handling ───────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        committedNode.setPosition(0, 0, w, h)
        // redrawCanvas records white → template → strokes into the node in one pass, so any strokes
        // loaded before layout (race with loadStrokes()) are not lost on first record.
        redrawCanvas()
        // First layout after attach (the setup deferred until real dimensions existed) and
        // any later resize (the disable-area screen offsets shift with layout). Idempotent.
        if (isAttachedToWindow) setupFirmwareInk()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pen-activity gate: all ink on this engine arrives as MotionEvents, so tracking the
        // stylus here covers every mode. Runs before any per-mode dispatch/early return.
        val gateToolType = event.getToolType(0)
        if (gateToolType == MotionEvent.TOOL_TYPE_STYLUS || gateToolType == MotionEvent.TOOL_TYPE_ERASER) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    penDown = true
                    strokeSawBarrel = false   // updateBarrelSuppress re-sets it just below
                    // Fresh EMR contact: fire any armed gesture-trace clear before this
                    // contact produces new overlay ink (covers all modes — the gate runs
                    // before per-mode dispatch).
                    if (firmware && overlayClearArmed) flushArmedOverlayClear()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    penDown = false
                    penLastLiftMs = SystemClock.uptimeMillis()
                }
            }
            // Contact-time button changes (pressed at pen-down, released at lift, or
            // toggled mid-stroke) — hover tracking alone would miss them.
            updateBarrelSuppress(event)
        }

        if (isTextPlacementMode) return handleTextPlacementTouch(event)
        // In any mode that bypasses the SDK raw-drawing path, intercept stylus button/eraser-end
        // events before the per-mode handler drops them. Check both TOOL_TYPE_ERASER and
        // BUTTON_STYLUS_PRIMARY to cover all platform/stylus variants.
        if (isTextPlacementMode || isLassoMode || isLassoEraserMode || isShapeTransformMode) {
            val t = event.getToolType(0)
            if (t == MotionEvent.TOOL_TYPE_ERASER
                || (t == MotionEvent.TOOL_TYPE_STYLUS
                    && (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)) {
                return handleBarrelButtonErase(event)
            }
        }
        if (isShapeTransformMode) return handleShapeTransformTouch(event)
        if (isLassoMode) return handleLassoTouch(event)
        if (isLassoEraserMode) return handleLassoEraserTouch(event)

        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false

        // strokeSawBarrel makes the decision STICKY for the whole contact: the button is
        // routinely released a beat before the pen lifts (measured on the Nomad —
        // ACTION_BUTTON_RELEASE arrives mid-contact), and without it the pen-up takes the
        // normal-pen branch and commits the stale down/up points as a phantom 2-point stroke.
        val erasing = toolType == MotionEvent.TOOL_TYPE_ERASER || isEraserActive
            || (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            || strokeSawBarrel

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Slog.d(TAG) { "penDOWN erasing=$erasing tool=$toolType btn=${event.buttonState} " +
                    "eraserActive=$isEraserActive barrelDown=$barrelDown" }
                // Eraser contact (eraser end / barrel button / eraser tool) is a handoff
                // boundary: bake pending overlay ink first so the software erase + redraw
                // operates on a fully-baked page (the firmware natively wipes only its own
                // overlay pixels along the eraser-end path — not the strokes we re-record).
                if (erasing && firmware) releaseFirmwareOverlay()
                strokeBeganErasing = erasing
                activePoints.clear()
                gestureHadInk = false
                if (erasing) activePoints.add(PointF(event.x, event.y))
                else appendStrokePoints(listOf(PointF(event.x, event.y)))
                strokeStartTimeMs = System.currentTimeMillis()
                dwellAnchorX   = event.x
                dwellAnchorY   = event.y
                lastMoveTimeMs = System.currentTimeMillis()
                dwellMs        = 0L
                if (!firmware) invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val newPoints = mutableListOf<PointF>()
                for (i in 0 until event.historySize) {
                    newPoints.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                newPoints.add(PointF(event.x, event.y))
                if (erasing) {
                    eraseAtPath(newPoints)
                } else {
                    appendStrokePoints(newPoints)
                    // Update dwell anchor: if any point moves beyond the dwell radius, reset.
                    val dwellRadiusPx = SHAPE_DWELL_RADIUS_DP * resources.displayMetrics.density
                    val now = System.currentTimeMillis()
                    for (pt in newPoints) {
                        val dx = pt.x - dwellAnchorX
                        val dy = pt.y - dwellAnchorY
                        if (dx * dx + dy * dy > dwellRadiusPx * dwellRadiusPx) {
                            dwellAnchorX   = pt.x
                            dwellAnchorY   = pt.y
                            lastMoveTimeMs = now
                        }
                    }
                    // The firmware paints the live trace; the per-move invalidate → full-view
                    // redraw → EPD panel update was the Generic engine's latency.
                    if (!firmware) invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Slog.d(TAG) { "penUP erasing=$erasing btn=${event.buttonState} " +
                    "sawBarrel=$strokeSawBarrel beganErasing=$strokeBeganErasing " +
                    "activePts=${activePoints.size}" }
                if (erasing) {
                    eraseAtPath(listOf(PointF(event.x, event.y)))
                    finalizeEraseRedraw()
                    activePoints.clear()
                    invalidate()
                    // Every erase contact ends with the clear ladder: the pen-down
                    // bake+clear can be eaten (measured on the Nomad — an erased stroke's
                    // overlay twin stayed frozen on the panel through the whole erase,
                    // hiding the repaint until a page flip reconciled it), and a contact
                    // that converted to erasing mid-stroke leaves an abandoned partial pen
                    // stroke on the overlay. Idempotent and invisible when already clean.
                    if (firmware) releaseGestureTrace()
                } else {
                    appendStrokePoints(listOf(PointF(event.x, event.y)))
                    commitActiveStroke()
                    activePoints.clear()
                    // Firmware path: the overlay keeps showing the stroke — no repaint, no
                    // clear, no refresh here (the deferred handoff). Invalidating now would
                    // trigger a pointless EPD update of unchanged committed content.
                    if (!firmware) invalidate()
                    // A gesture that never left the exclusion zone committed nothing — do
                    // not dispatch, or the gates would re-examine a stale earlier stroke.
                    if (gestureHadInk) {
                        val now = System.currentTimeMillis()
                        dwellMs = now - lastMoveTimeMs
                        val durationMs = (now - strokeStartTimeMs).coerceAtLeast(1L)
                        checkAndDispatchGesture(durationMs)
                    }
                }
            }
        }
        return true
    }

    private fun handleBarrelButtonErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                eraseAtPath(listOf(PointF(event.x, event.y)))
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = mutableListOf<PointF>()
                for (i in 0 until event.historySize) pts.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                pts.add(PointF(event.x, event.y))
                eraseAtPath(pts)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                eraseAtPath(listOf(PointF(event.x, event.y)))
                finalizeEraseRedraw()
                activePoints.clear()
                invalidate()
                // Erase contact in a Canvas-overlay mode: any partial firmware trail from
                // before the button/eraser-end took over must not linger (Phase 4 rule —
                // every erase contact arms the ladder; idempotent when already clean).
                if (firmware) releaseGestureTrace()
                onPenLifted?.invoke()
            }
        }
        return true
    }

    private fun handleTextPlacementTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record the press point but stay in placement mode so MOVE/UP are
                // also consumed here — not leaked to the normal stroke-drawing path.
                textPlacementTapX = event.x
                textPlacementTapY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTextPlacementMode = false
                // Internal mode exit — restore the armed tool's firmware state before the
                // host opens the editor (whose focus loss/gain re-runs setup anyway).
                if (firmware) applyToolToFirmware()
                onTextPlacementTap?.invoke(textPlacementTapX, textPlacementTapY)
            }
        }
        return true
    }

    private fun handleLassoTouch(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Only stylus builds the lasso path / drag; finger taps fall through to false.
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) return false

        val thresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Check if pen-down is inside the current selection box — if so, start a drag.
                val box = lassoSelectionBox
                if (box != null && lassoSelectedIds.isNotEmpty() && box.contains(event.x, event.y)) {
                    isDragMoveActive = true
                    // A drag must not leave a dashed trail. The REAL suppress happened on the
                    // hover stream before the tip landed (updateLassoDragHoverSuppress — a
                    // disable issued here is too late, the firmware latches pen state as the
                    // contact begins); this one is only the backstop for a contact that
                    // arrived with no hover warning. ACTION_UP re-arms via applyToolToFirmware.
                    if (firmware) SupernoteInk.setFullScreenDisable(width, height)
                    dragStartX = event.x; dragStartY = event.y
                    dragThresholdMet = false
                    dragDx = 0f; dragDy = 0f
                    dragOriginalStrokes = strokes
                        .filter { it.id in lassoSelectedIds }
                        .map { it.deepCopy() }
                    dragOriginalHeadings = headings
                        .filter { it.id in lassoSelectedIds }
                        .map { h -> HeadingStroke(h.id, android.graphics.RectF(h.boundingBox),
                            h.strokes.map { s -> s.deepCopy() },
                            recognizedText = h.recognizedText,
                            level = h.level) }
                    dragOriginalTextObjects = textObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { TextRender(it.id, RectF(it.boundingBox), it.text) }
                    dragOriginalLineObjects = lineObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { it.copy(boundingBox = RectF(it.boundingBox)) }
                    dragOriginalLinks = links
                        .filter { it.id in lassoSelectedIds }
                        .map { it.translate(0f, 0f) }
                    dragOriginalStickyNotes = stickyNotes
                        .filter { it.id in lassoSelectedIds }
                        .map { it.translate(0f, 0f) }
                    dragOriginalShapeObjects = shapeObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { it.copy(boundingBox = RectF(it.boundingBox)) }
                    val nonSelectedStrokes  = strokes.filter { it.id !in lassoSelectedIds }
                    val nonSelectedHeadings = headings.filter { it.id !in lassoSelectedIds }
                    val nonSelectedTexts    = textObjects.filter { it.id !in lassoSelectedIds }
                    val nonSelectedLines    = lineObjects.filter { it.id !in lassoSelectedIds }
                    val nonSelectedLinks    = links.filter { it.id !in lassoSelectedIds }
                    val nonSelectedStickyNotes = stickyNotes.filter { it.id !in lassoSelectedIds }
                    val nonSelectedShapes = shapeObjects.filter { it.id !in lassoSelectedIds }
                    dragBackingBitmap = buildRenderBitmap(nonSelectedStrokes, templateBitmap, nonSelectedHeadings, nonSelectedTexts, nonSelectedLines, nonSelectedLinks, nonSelectedStickyNotes, shapeObjects = nonSelectedShapes)
                    snapObjectTargets = if (isSnapEnabled) (nonSelectedHeadings.map { RectF(it.boundingBox) } + nonSelectedTexts.map { RectF(it.boundingBox) } + nonSelectedLines.map { RectF(it.boundingBox) } + nonSelectedLinks.map { RectF(it.boundingBox) }) else emptyList()
                    return true
                }
                // Normal lasso: clear any existing selection so the user sees immediate feedback.
                lassoGestureHadSelection = lassoSelectionBox != null
                lassoPreClearSelectionBox = lassoSelectionBox?.let { RectF(it) }
                lassoSelectionBox = null
                lassoOverlayPath  = null
                invalidate()
                if (lassoGestureHadSelection) onLassoSelectionCleared?.invoke()
                lassoGesturePath = Path().also { it.moveTo(event.x, event.y) }
                lassoGestureStartPoint = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragMoveActive) {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    if (!dragThresholdMet) {
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (dist >= thresholdPx) {
                            dragThresholdMet = true
                            onDragStarted?.invoke()
                        }
                    }
                    if (dragThresholdMet) {
                        if (isSnapEnabled) {
                            val density = resources.displayMetrics.density
                            val snap = SnapEngine.computeSnap(
                                lassoSelectionBox ?: RectF(),
                                dx, dy,
                                width.toFloat(), height.toFloat(),
                                SNAP_MARGIN_DP * density,
                                SNAP_THRESHOLD_DP * density,
                                snapObjectTargets,
                            )
                            dragDx = snap.snappedDx; dragDy = snap.snappedDy
                            activeSnapGuides = snap.activeGuides
                        } else {
                            dragDx = dx; dragDy = dy
                            activeSnapGuides = emptyList()
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                            lastLassoRefreshMs = now
                            invalidate()
                        }
                    }
                    return true
                }
                val path = lassoGesturePath ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                // Firmware path (Phase 5): the panel paints the dashed trail under the pen —
                // no Canvas overlay, no throttled invalidate (that throttle WAS the visible lag).
                if (!firmware) {
                    val now = System.currentTimeMillis()
                    if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                        lastLassoRefreshMs = now
                        lassoOverlayPath = path
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragMoveActive) {
                    if (dragThresholdMet) {
                        val movedStrokes = dragOriginalStrokes.map { stroke ->
                            stroke.copy(points = stroke.points.map { pt ->
                                PointF(pt.x + dragDx, pt.y + dragDy)
                            })
                        }
                        val movedHeadings = dragOriginalHeadings.map { h ->
                            HeadingStroke(
                                id = h.id,
                                boundingBox = android.graphics.RectF(
                                    h.boundingBox.left + dragDx, h.boundingBox.top + dragDy,
                                    h.boundingBox.right + dragDx, h.boundingBox.bottom + dragDy,
                                ),
                                strokes = h.strokes.map { s ->
                                    s.copy(points = s.points.map { PointF(it.x + dragDx, it.y + dragDy) })
                                },
                                recognizedText = h.recognizedText,
                                level = h.level,
                            )
                        }
                        val movedTextObjects = dragOriginalTextObjects.map { t ->
                            TextRender(t.id, RectF(
                                t.boundingBox.left + dragDx, t.boundingBox.top + dragDy,
                                t.boundingBox.right + dragDx, t.boundingBox.bottom + dragDy,
                            ), t.text)
                        }
                        val movedLineObjects = dragOriginalLineObjects.map { l ->
                            l.copy(
                                boundingBox = RectF(
                                    l.boundingBox.left + dragDx, l.boundingBox.top + dragDy,
                                    l.boundingBox.right + dragDx, l.boundingBox.bottom + dragDy,
                                ),
                                startX = l.startX + dragDx, startY = l.startY + dragDy,
                                endX   = l.endX   + dragDx, endY   = l.endY   + dragDy,
                            )
                        }
                        val movedLinks = dragOriginalLinks.map { it.translate(dragDx, dragDy) }
                        val movedStickyNotes = dragOriginalStickyNotes.map { it.translate(dragDx, dragDy) }
                        val movedShapes = dragOriginalShapeObjects.map { s ->
                            s.copy(
                                centerX = s.centerX + dragDx,
                                centerY = s.centerY + dragDy,
                                boundingBox = RectF(s.boundingBox).apply { offset(dragDx, dragDy) },
                            )
                        }
                        val movedById = movedStrokes.associateBy { it.id }
                        val updated = strokes.map { movedById[it.id] ?: it }
                        strokes.clear(); strokes.addAll(updated)
                        val headingById = movedHeadings.associateBy { it.id }
                        headings = headings.map { headingById[it.id] ?: it }
                        val textById = movedTextObjects.associateBy { it.id }
                        textObjects = textObjects.map { textById[it.id] ?: it }
                        val lineById = movedLineObjects.associateBy { it.id }
                        lineObjects = lineObjects.map { lineById[it.id] ?: it }
                        val linkById = movedLinks.associateBy { it.id }
                        links = links.map { linkById[it.id] ?: it }
                        val stickyById = movedStickyNotes.associateBy { it.id }
                        stickyNotes = stickyNotes.map { stickyById[it.id] ?: it }
                        val shapeById = movedShapes.associateBy { it.id }
                        shapeObjects = shapeObjects.map { shapeById[it.id] ?: it }
                        lassoSelectionBox = lassoSelectionBox?.let { b ->
                            RectF(b.left + dragDx, b.top + dragDy, b.right + dragDx, b.bottom + dragDy)
                        }
                        val origStrokes = dragOriginalStrokes
                        val origHeadings = dragOriginalHeadings
                        val origTextObjects = dragOriginalTextObjects
                        val origLineObjects = dragOriginalLineObjects
                        val origLinks = dragOriginalLinks
                        val origStickyNotes = dragOriginalStickyNotes
                        val origShapes = dragOriginalShapeObjects
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
                        dragOriginalShapeObjects = emptyList()
                        // Drag over — re-arm the lasso trail pen (drag entry full-screen-disabled).
                        if (firmware) applyToolToFirmware()
                        redrawCanvas()
                        onStrokesMoved?.invoke(origStrokes, movedStrokes, origHeadings, movedHeadings, origTextObjects, movedTextObjects, origLineObjects, movedLineObjects, origLinks, movedLinks, origStickyNotes, movedStickyNotes, origShapes, movedShapes)
                    } else {
                        // Below threshold — treat as a tap inside the selection box.
                        val tapX = event.x; val tapY = event.y
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
                        dragOriginalShapeObjects = emptyList()
                        if (firmware) applyToolToFirmware()
                        onLassoTap?.invoke(tapX, tapY)
                    }
                    return true
                }
                val path = lassoGesturePath ?: return true
                val start = lassoGestureStartPoint ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                lassoGesturePath = null
                lassoGestureStartPoint = null
                lassoOverlayPath = null
                invalidate()
                // The firmware dashed trail corresponds to nothing in the app layer — wipe
                // it with the proven gesture-trace ladder before the selection box appears.
                // Runs on the tap outcome too (a tap paints a dash dot at the contact point).
                if (firmware) releaseGestureTrace()
                // Tap vs lasso: use the gesture's overall extent, not net start→end displacement.
                // A small circular lasso returns near its origin (tiny net displacement) but spans
                // a real bounding box — displacement alone would misclassify it as a tap and paste.
                val gestureBounds = RectF()
                path.computeBounds(gestureBounds, true)
                if (gestureBounds.width() < thresholdPx && gestureBounds.height() < thresholdPx) {
                    val hadSelection = lassoGestureHadSelection
                    lassoGestureHadSelection = false
                    val savedBox = lassoPreClearSelectionBox
                    lassoPreClearSelectionBox = null
                    if (hadSelection && savedBox != null && savedBox.contains(event.x, event.y)) {
                        onLassoTap?.invoke(event.x, event.y)
                    } else {
                        onLassoTapToDismiss?.invoke()
                        if (!hadSelection) onLassoTap?.invoke(event.x, event.y)
                    }
                } else {
                    lassoGestureHadSelection = false
                    lassoPreClearSelectionBox = null
                    onLassoComplete?.invoke(path, start)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Drag layer: non-selected backing + selected headings/textObjects/strokes translated.
        if (isDragMoveActive && dragThresholdMet) {
            canvas.drawColor(Color.WHITE)
            dragBackingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
            for (heading in dragOriginalHeadings) {
                if (heading.recognizedText != null) {
                    drawHeadingText(canvas, heading)
                } else {
                    for (stroke in heading.strokes) drawStrokePath(canvas, stroke)
                }
            }
            for (textObj in dragOriginalTextObjects) {
                drawTextObject(canvas, textObj, width)
            }
            for (lineObj in dragOriginalLineObjects) {
                drawLineObject(canvas, lineObj)
            }
            for (shape in dragOriginalShapeObjects) {
                drawShapeObject(canvas, shape)
            }
            for (link in dragOriginalLinks) {
                drawLinkObject(canvas, link, width)
            }
            for (note in dragOriginalStickyNotes) {
                drawStickyNoteObject(canvas, note)
            }
            for (stroke in dragOriginalStrokes) drawStrokePath(canvas, stroke)
            canvas.restoreToCount(save)
            lassoSelectionBox?.let { box ->
                canvas.drawRect(
                    RectF(box.left + dragDx, box.top + dragDy, box.right + dragDx, box.bottom + dragDy),
                    lassoPaint,
                )
            }
            drawSnapGuides(canvas)
            return
        }

        // Committed content: blit the cached hardware RenderNode; fall back to drawing the vector
        // content directly on a software canvas or before the node's first record.
        if (canvas.isHardwareAccelerated && committedNode.hasDisplayList()) {
            canvas.drawRenderNode(committedNode)
        } else {
            drawCommittedContent(canvas)
        }

        // Shape transform overlay — drawn on top of the committed content.
        if (isShapeTransformMode) {
            val r = transformController.getWorkingRender()
            if (r != null) {
                drawShapeObject(canvas, r)
                transformController.draw(canvas)
            }
        }

        // The in-progress stroke — the one case that takes the *armed* pen colour rather than a
        // stored one, since it has no LiveStroke yet. It is committed with the same hex.
        // On the firmware path the EPDC overlay paints the live stroke instead.
        if (!firmware && !isEraserActive && !isLassoMode && activePoints.size >= 2) {
            val path = Path()
            path.moveTo(activePoints[0].x, activePoints[0].y)
            for (i in 1 until activePoints.size) {
                path.lineTo(activePoints[i].x, activePoints[i].y)
            }
            strokePaint.color = penColorInt
            canvas.drawPath(path, strokePaint)
        }

        // Lasso overlay — drawn on top of everything.
        if (isLassoEraserMode) {
            lassoEraserDisplayPath?.let { canvas.drawPath(it, lassoEraserPaint) }
        } else {
            lassoOverlayPath?.let { canvas.drawPath(it, lassoPaint) }
            lassoSelectionBox?.let { canvas.drawRect(it, lassoPaint) }
        }
    }

    /** True once the current pen gesture committed at least one stroke segment. */
    private var gestureHadInk = false

    /**
     * Append live points, splitting the stroke around the toolbar exclusion zone so the
     * model never holds ink the firmware refused to paint. Without this, stylus events
     * falling through gaps in the chrome (the overflow menu's blank areas don't consume
     * touches) would silently enter the model and surface at the next bake — invisible
     * strokes appearing later. Segments outside the zone commit as separate strokes,
     * matching exactly what the firmware painted; points inside are dropped.
     */
    private fun appendStrokePoints(pts: List<PointF>) {
        val excl = if (firmware) toolbarExclusion else null
        if (excl == null || excl.isEmpty) {
            activePoints.addAll(pts)
            return
        }
        for (p in pts) {
            if (excl.contains(p.x.toInt(), p.y.toInt())) {
                commitActiveStroke()   // no-op below 2 points
                activePoints.clear()
            } else {
                activePoints.add(p)
            }
        }
    }

    private fun commitActiveStroke() {
        if (activePoints.size < 2) return
        gestureHadInk = true
        Slog.d(TAG) { "commit stroke pts=${activePoints.size}" }
        val strokeId = UUID.randomUUID().toString()
        val strokePoints = activePoints.toList()
        strokes.add(LiveStroke(strokeId, strokePoints, color = penColorHex))
        if (firmware) {
            // Deferred handoff: the stroke is in the model NOW (saves, snapshots, erase
            // hit-tests and the gesture gates all see it) but the visual bake waits for a
            // boundary — the firmware overlay keeps showing the ink until then.
            pendingBake = true
        } else {
            // Re-record the committed node so the finished stroke is baked in (records a display list only).
            redrawCanvas()
        }
    }

    private fun eraseAtPath(eraserPoints: List<PointF>) {
        if (eraserPoints.isEmpty()) return
        val thresholdSq = ERASER_RADIUS_PX * ERASER_RADIUS_PX

        // Build the expanded AABB of the entire eraser path for fast stroke pre-rejection.
        var eMinX = eraserPoints[0].x; var eMinY = eraserPoints[0].y
        var eMaxX = eMinX;             var eMaxY = eMinY
        for (ep in eraserPoints) {
            if (ep.x < eMinX) eMinX = ep.x else if (ep.x > eMaxX) eMaxX = ep.x
            if (ep.y < eMinY) eMinY = ep.y else if (ep.y > eMaxY) eMaxY = ep.y
        }
        val eBounds = android.graphics.RectF(
            eMinX - ERASER_RADIUS_PX, eMinY - ERASER_RADIUS_PX,
            eMaxX + ERASER_RADIUS_PX, eMaxY + ERASER_RADIUS_PX
        )

        // Heading hit-test: erase entire heading if eraser AABB intersects its bounding box.
        val hitHeadings = headings.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitHeadings.isNotEmpty()) {
            val hitIds = hitHeadings.mapTo(HashSet()) { it.id }
            headings = headings.filter { it.id !in hitIds }
            hitHeadings.forEach { onHeadingErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Text-object hit-test: erase entire text object if eraser AABB intersects its box.
        val hitTexts = textObjects.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitTexts.isNotEmpty()) {
            val hitIds = hitTexts.mapTo(HashSet()) { it.id }
            textObjects = textObjects.filter { it.id !in hitIds }
            hitTexts.forEach { onTextErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Line-object hit-test: erase entire line if eraser AABB intersects its box.
        val hitLines = lineObjects.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitLines.isNotEmpty()) {
            val hitIds = hitLines.mapTo(HashSet()) { it.id }
            lineObjects = lineObjects.filter { it.id !in hitIds }
            hitLines.forEach { onLineErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Link-object hit-test: erase the entire link if eraser AABB intersects its box.
        val hitLinks = links.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitLinks.isNotEmpty()) {
            val hitIds = hitLinks.mapTo(HashSet()) { it.id }
            links = links.filter { it.id !in hitIds }
            hitLinks.forEach { onLinkErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Sticky-note hit-test: erase the entire note if eraser AABB intersects its icon box.
        val hitStickyNotes = stickyNotes.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitStickyNotes.isNotEmpty()) {
            val hitIds = hitStickyNotes.mapTo(HashSet()) { it.id }
            stickyNotes = stickyNotes.filter { it.id !in hitIds }
            hitStickyNotes.forEach { onStickyNoteErased?.invoke(it) }
            throttledEraseRedraw()
        }

        val hitShapes = shapeObjects.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitShapes.isNotEmpty()) {
            val hitIds = hitShapes.mapTo(HashSet()) { it.id }
            shapeObjects = shapeObjects.filter { it.id !in hitIds }
            hitShapes.forEach { onShapeErased?.invoke(it) }
            throttledEraseRedraw()
        }

        val toRemove = strokes.filter { stroke ->
            // Fast AABB rejection — O(1) per stroke, eliminates ~95% of candidates.
            android.graphics.RectF.intersects(eBounds, stroke.boundingBox) &&
            // Detailed per-point geometry only for strokes that passed the box check.
            stroke.points.any { sp ->
                eraserPoints.indices.drop(1).any { i ->
                    pointToSegmentDistSq(sp, eraserPoints[i - 1], eraserPoints[i]) <= thresholdSq
                } || pointToPointDistSq(sp, eraserPoints[0]) <= thresholdSq
            }
        }
        if (toRemove.isNotEmpty()) {
            Slog.d(TAG) { "erase removed=${toRemove.size} strokes" }
            val removeIds = toRemove.mapTo(HashSet(toRemove.size)) { it.id }
            strokes.removeAll { it.id in removeIds }
            toRemove.forEach { onStrokeErased?.invoke(it.id) }
            throttledEraseRedraw()
        }
    }

    /**
     * Redraw at most once every [ERASE_REDRAW_INTERVAL_MS] during active erasing.
     * Strokes are already removed from [strokes] before this is called.
     */
    private fun throttledEraseRedraw() {
        val now = System.currentTimeMillis()
        if (now - lastEraseRedrawMs >= ERASE_REDRAW_INTERVAL_MS) {
            lastEraseRedrawMs = now
            redrawCanvas()
        }
    }

    /**
     * Force an immediate redraw at gesture end — flushes any throttled removals so
     * the canvas is always correct on pen lift.
     */
    private fun finalizeEraseRedraw() {
        lastEraseRedrawMs = System.currentTimeMillis()
        redrawCanvas()
    }

    private fun drawHeadingText(canvas: Canvas, heading: HeadingStroke) {
        val text = heading.recognizedText ?: return
        val box = heading.boundingBox
        val paddingPx = 8f * resources.displayMetrics.density
        val innerBox = android.graphics.RectF(box.left + paddingPx, box.top + paddingPx, box.right - paddingPx, box.bottom - paddingPx)
        val widthPx = kotlin.math.ceil(innerBox.width().toDouble()).toInt().coerceAtLeast(1)
        canvas.save()
        canvas.clipRect(box)
        TextObjectRenderer.draw(canvas, TextRender(heading.id, innerBox, text), widthPx, textObjectPaint, resources.displayMetrics.density, maxLines = 1)
        canvas.restore()
    }

    /**
     * Render a type="text" object onto [canvas].
     * - Non-blank text → markdown path via TextObjectRenderer.
     * - Blank text + non-empty strokes → render embedded strokes (unrecognized conversion state).
     * - Blank text + no strokes → nothing rendered.
     */
    private fun drawTextObject(canvas: Canvas, textObj: TextRender, widthPx: Int) {
        when {
            textObj.text.isNotBlank() ->
                TextObjectRenderer.draw(canvas, textObj, widthPx, textObjectPaint, resources.displayMetrics.density)
            !textObj.strokes.isNullOrEmpty() -> {
                for (liveStroke in textObj.strokes) drawStrokePath(canvas, liveStroke)
            }
        }
    }

    /**
     * Render a type="line" object onto [canvas].
     * Line style (solid/dashed/dotted) is applied via PathEffect on a transient Paint.
     */
    private fun drawLineObject(canvas: Canvas, lineObj: LineRender) {
        val density = resources.displayMetrics.density
        val sw = lineObj.strokeWidthDp * density
        val paint = Paint().apply {
            isAntiAlias = true
            color = context.getColor(R.color.inkLight)
            strokeCap = Paint.Cap.ROUND
            strokeWidth = sw
        }
        when (lineObj.style) {
            com.notesprout.android.data.LineStyle.SOLID -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            com.notesprout.android.data.LineStyle.DASHED -> {
                paint.style = Paint.Style.STROKE
                paint.pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            com.notesprout.android.data.LineStyle.DOTTED -> {
                paint.style = Paint.Style.FILL
                val spacing = lineObj.dotSpacingPx.takeIf { it > 0f } ?: (sw * 4f)
                val r = sw / 2f
                when (lineObj.orientation) {
                    com.notesprout.android.data.LineOrientation.HORIZONTAL -> {
                        var x = lineObj.startX
                        while (x <= lineObj.endX) { canvas.drawCircle(x, lineObj.startY, r, paint); x += spacing }
                    }
                    com.notesprout.android.data.LineOrientation.VERTICAL -> {
                        var y = lineObj.startY
                        while (y <= lineObj.endY) { canvas.drawCircle(lineObj.startX, y, r, paint); y += spacing }
                    }
                }
            }
        }
    }

    private fun drawShapeObject(canvas: Canvas, shape: ShapeRender) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = shape.strokeWidthPx
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(ShapeGeometry.pathFor(shape), paint)
    }

    /**
     * Render a type="link" object onto [canvas]: its embedded content (headings, text, lines,
     * strokes) painted via the existing per-type helpers, then the chrome around the union bbox.
     * Links are drawn after page lines and before top-level strokes (see redrawCanvas).
     */
    private fun drawLinkObject(canvas: Canvas, link: LinkRender, widthPx: Int) {
        for (heading in link.headings) {
            if (heading.recognizedText != null) {
                drawHeadingText(canvas, heading)
            } else {
                for (liveStroke in heading.strokes) drawStrokePath(canvas, liveStroke)
            }
        }
        for (textObj in link.textObjects) drawTextObject(canvas, textObj, widthPx)
        for (lineObj in link.lines) drawLineObject(canvas, lineObj)
        for (shape in link.shapes) drawShapeObject(canvas, shape)
        for (liveStroke in link.strokes) drawStrokePath(canvas, liveStroke)
        val iconOutside = link.headings.isNotEmpty() || link.textObjects.isNotEmpty()
        drawLinkChrome(canvas, link.boundingBox, link.chrome, iconOutside)
    }

    private fun drawStickyNoteObject(canvas: Canvas, note: StickyNoteRender) {
        val box = note.boundingBox
        AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.let { icon ->
            icon.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
            icon.draw(canvas)
        }
    }

    /** Draw a link's visual indicator: none, an underline, or a dotted box with a corner chevron.
     *  For text/heading links [iconOutside]=true: the stored bbox already includes the gap + icon
     *  room baked in at creation (6dp gap + 14dp icon + 3dp inner pad), so the chrome box IS the
     *  bbox and the icon is placed inside at bottom-right. */
    private fun drawLinkChrome(canvas: Canvas, box: RectF, chrome: LinkChrome, iconOutside: Boolean = false) {
        when (chrome) {
            LinkChrome.NONE -> {}
            LinkChrome.UNDERLINE ->
                canvas.drawLine(box.left, box.bottom, box.right, box.bottom, linkChromePaint)
            LinkChrome.DOTTED_CHEVRON -> {
                val d = resources.displayMetrics.density
                val iconSize = (14f * d).toInt()
                val pad = 3f * d
                if (iconOutside) {
                    // bbox already contains the full visual extent; draw it as-is.
                    canvas.drawRect(box, linkChromeDashPaint)
                    val iconLeft = (box.right - iconSize - pad).toInt()
                    val iconBottom = (box.bottom - pad).toInt()
                    AppCompatResources.getDrawable(context, R.drawable.ic_link)?.let { icon ->
                        icon.setBounds(iconLeft, iconBottom - iconSize, iconLeft + iconSize, iconBottom)
                        icon.draw(canvas)
                    }
                } else {
                    canvas.drawRect(box, linkChromeDashPaint)
                    val iconRight = (box.right - pad).toInt()
                    val iconBottom = (box.bottom - pad).toInt()
                    AppCompatResources.getDrawable(context, R.drawable.ic_link)?.let { icon ->
                        icon.setBounds(iconRight - iconSize, iconBottom - iconSize, iconRight, iconBottom)
                        icon.draw(canvas)
                    }
                }
            }
        }
    }

    /**
     * Redraws the render bitmap from scratch: white base → template → all current strokes.
     * Call whenever strokes are added/removed or the template changes.
     */
    private fun drawSnapGuides(canvas: Canvas) {
        if (activeSnapGuides.isEmpty()) return
        for (guide in activeSnapGuides) {
            when (guide) {
                is SnapGuide.Vertical   -> canvas.drawLine(guide.x, 0f, guide.x, height.toFloat(), snapGuidePaint)
                is SnapGuide.Horizontal -> canvas.drawLine(0f, guide.y, width.toFloat(), guide.y, snapGuidePaint)
            }
        }
    }

    private fun redrawCanvas() {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        committedNode.setPosition(0, 0, w, h)
        val rc = committedNode.beginRecording(w, h)
        try {
            drawCommittedContent(rc)
        } finally {
            committedNode.endRecording()
        }
        // Every re-record bakes the whole [strokes] list — pending overlay-shown strokes
        // included. If the firmware overlay was still showing them, hand off now so the same
        // ink is never displayed by both layers at once. This guard also keeps any redraw
        // triggered outside releaseFirmwareOverlay (erase paths, drag commit) correct.
        if (firmware && pendingBake) {
            pendingBake = false
            SupernoteInk.clearAll()
        }
        invalidate()
    }

    /** Draw the full committed page onto [canvas] — records the node and serves as the [onDraw]
     *  software fallback (a [RenderNode] can't be drawn on a software canvas). */
    private fun drawCommittedContent(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { tb ->
            canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        }
        for (heading in headings) {
            if (heading.recognizedText != null) {
                drawHeadingText(canvas, heading)
            } else {
                for (liveStroke in heading.strokes) drawStrokePath(canvas, liveStroke)
            }
        }
        for (textObj in textObjects) {
            drawTextObject(canvas, textObj, width)
        }
        for (lineObj in lineObjects) {
            drawLineObject(canvas, lineObj)
        }
        for (shape in shapeObjects) {
            drawShapeObject(canvas, shape)
        }
        for (link in links) {
            drawLinkObject(canvas, link, width)
        }
        for (note in stickyNotes) {
            drawStickyNoteObject(canvas, note)
        }
        for (liveStroke in strokes) drawStrokePath(canvas, liveStroke)
    }

    // Minimum squared distance from point p to segment a→b.
    private fun pointToSegmentDistSq(p: PointF, a: PointF, b: PointF): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq == 0f) return pointToPointDistSq(p, a)
        val t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
        val cx = a.x + t.coerceIn(0f, 1f) * abx
        val cy = a.y + t.coerceIn(0f, 1f) * aby
        val dx = p.x - cx
        val dy = p.y - cy
        return dx * dx + dy * dy
    }

    private fun pointToPointDistSq(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    // ── Gesture detection at pen lift (shape-dwell → smart-lasso → scribble-to-erase → normal) ──

    /**
     * Called after [commitActiveStroke] on every non-eraser pen lift. Runs the detection
     * gate chain in priority order on a single background thread:
     *   Gate 0 — Shape dwell: single stroke held still ≥ [SHAPE_DWELL_MS] → shape object.
     *            Currently off via [SHAPE_DWELL_ENABLED]; the gate is skipped entirely.
     *   Gate 1 — Smart lasso: fast closed circle enclosing ≥1 object → enter lasso selection.
     *   Gate 2 — Scribble-to-erase: dense back-and-forth crossing ≥1 object → erase.
     *   Default — Normal stroke: fire [onPenLifted] so the activity saves the stroke to DB.
     */
    private fun checkAndDispatchGesture(durationMs: Long) {
        val lastStroke = strokes.lastOrNull()
        if (lastStroke == null) { onPenLifted?.invoke(); return }

        val points    = lastStroke.points
        val density   = resources.displayMetrics.density
        val strokeId  = lastStroke.id

        // ── Gate 0 pre-check: single-stroke dwell candidate → always spawn a thread ──
        val dwellCandidate = SHAPE_DWELL_ENABLED && dwellMs >= SHAPE_DWELL_MS

        val isSmartLasso = !dwellCandidate && isSmartLassoCandidate(points, durationMs, density)
        val isScribble   = !dwellCandidate && !isSmartLasso && isScribbleCandidate(points)
        Slog.d(TAG) { "gesture dwell=$dwellCandidate lasso=$isSmartLasso scribble=$isScribble" }
        if (!dwellCandidate && !isSmartLasso && !isScribble) { onPenLifted?.invoke(); return }

        val strokeSnapshot      = strokes.filter { it.id != strokeId }.toList()
        val headingSnapshot     = headings.toList()
        val textSnapshot        = textObjects.toList()
        val lineSnapshot        = lineObjects.toList()
        val linkSnapshot        = links.toList()
        val stickyNoteSnapshot  = stickyNotes.toList()
        val shapeSnapshot       = shapeObjects.toList()

        Thread {
            // ── Gate 0: Shape dwell trigger ────────────────────────────────────────
            // Runs first; on null falls through to gates 1 and 2.
            var runSmartLasso = isSmartLasso
            if (dwellCandidate) {
                val result = ShapeRecognizer.recognize(points, density)
                if (result != null) {
                    post {
                        strokes.removeAll { it.id == strokeId }
                        // The gesture stroke is firmware-overlay ink — bake the survivors and
                        // wipe the overlay so the consumed gesture leaves no trace.
                        releaseGestureTrace()
                        onShapeRecognized?.invoke(lastStroke, result)
                    }
                    return@Thread
                }
                // No shape recognized — check gates 1 and 2 for this stroke.
                runSmartLasso = isSmartLassoCandidate(points, durationMs, density)
                val runScribble = !runSmartLasso && isScribbleCandidate(points)
                if (!runSmartLasso && !runScribble) {
                    post { onPenLifted?.invoke() }
                    return@Thread
                }
            }

            // ── Gate 1: Smart lasso ────────────────────────────────────────────────
            if (runSmartLasso) {
                val path = Path().also { p ->
                    p.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
                    p.lineTo(points[0].x, points[0].y)
                    p.close()
                }
                val hitIds = runLassoHitTest(path, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot, linkSnapshot, stickyNoteSnapshot, shapeSnapshot)
                if (hitIds.isNotEmpty()) {
                    val hitSet      = hitIds.toSet()
                    val unionBounds = RectF()
                    for (s in strokeSnapshot) { if (s.id in hitSet) unionBounds.union(s.boundingBox) }
                    for (h in headingSnapshot) { if (h.id in hitSet) unionBounds.union(h.boundingBox) }
                    for (t in textSnapshot)    { if (t.id in hitSet) unionBounds.union(t.boundingBox) }
                    for (l in lineSnapshot)    { if (l.id in hitSet) unionBounds.union(l.boundingBox) }
                    for (lk in linkSnapshot)   { if (lk.id in hitSet) unionBounds.union(lk.boundingBox) }
                    for (sn in stickyNoteSnapshot) { if (sn.id in hitSet) unionBounds.union(sn.boundingBox) }
                    for (sh in shapeSnapshot) { if (sh.id in hitSet) unionBounds.union(sh.boundingBox) }
                    post {
                        strokes.removeAll { it.id == strokeId }
                        // Wipe the smart-lasso circle from the firmware overlay (and bake any
                        // earlier pending strokes) before the selection box appears.
                        releaseGestureTrace()
                        onSmartLassoComplete?.invoke(hitIds, unionBounds)
                    }
                    return@Thread
                }
                // Smart-lasso geometry passed but no objects enclosed — fall through to
                // scribble check (a fast circle over empty space is an unusual but valid stroke).
                if (!isScribbleCandidate(points)) {
                    post { onPenLifted?.invoke() }
                    return@Thread
                }
            }

            // ── Gate 2: Scribble-to-erase ──────────────────────────────────────────
            val hitIds = scribbleHitTest(points, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot, density, linkSnapshot, stickyNoteSnapshot, shapeSnapshot)
            post {
                if (hitIds.isEmpty()) {
                    onPenLifted?.invoke()
                } else {
                    strokes.removeAll { it.id == strokeId }
                    // Wipe the scribble from the firmware overlay (and bake any earlier
                    // pending strokes) before the host rebuilds the erased page.
                    releaseGestureTrace()
                    val hitSet            = hitIds.toSet()
                    val erasedHeadings    = headingSnapshot.filter { it.id in hitSet }
                    val erasedTexts       = textSnapshot.filter { it.id in hitSet }
                    val erasedLines       = lineSnapshot.filter { it.id in hitSet }
                    val erasedLinks       = linkSnapshot.filter { it.id in hitSet }
                    val erasedStickyNotes = stickyNoteSnapshot.filter { it.id in hitSet }
                    val erasedShapes      = shapeSnapshot.filter { it.id in hitSet }
                    shapeObjects = shapeObjects.filter { it.id !in hitSet }
                    onScribbleEraseComplete?.invoke(hitIds, erasedHeadings, erasedTexts, erasedLines, erasedLinks, erasedStickyNotes, erasedShapes)
                }
            }
        }.start()
    }

    /**
     * Returns true when [points] form a smart-lasso candidate — all three gates must pass:
     *  1. pathLength / durationMs ≥ [SMART_LASSO_MIN_VELOCITY] px/ms.
     *  2. Distance from first to last point ≤ [SMART_LASSO_CLOSURE_DISTANCE_DP] dp.
     *  3. The path winds ≥ [SMART_LASSO_MIN_WINDING_DEGREES]° around its centroid —
     *     i.e. the pen actually traced a loop, not a letter or open arc.
     */
    private fun isSmartLassoCandidate(points: List<PointF>, durationMs: Long, density: Float): Boolean {
        if (points.size < 4 || durationMs <= 0L) return false

        var pathLength = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLength += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        if (pathLength / durationMs < SMART_LASSO_MIN_VELOCITY) return false

        val closureThresholdPx = SMART_LASSO_CLOSURE_DISTANCE_DP * density
        val first = points[0]; val last = points[points.size - 1]
        val cdx = last.x - first.x; val cdy = last.y - first.y
        if (Math.sqrt((cdx * cdx + cdy * cdy).toDouble()).toFloat() > closureThresholdPx) return false

        // Winding check: accumulate signed angular change around the gesture centroid.
        // Letters and open arcs never wind 270°+ around a central point; loops always do.
        var cx = 0f; var cy = 0f
        for (p in points) { cx += p.x; cy += p.y }
        cx /= points.size; cy /= points.size

        var totalAngle = 0.0
        var prevAngle = Math.atan2((points[0].y - cy).toDouble(), (points[0].x - cx).toDouble())
        for (i in 1 until points.size) {
            val angle = Math.atan2((points[i].y - cy).toDouble(), (points[i].x - cx).toDouble())
            var delta = angle - prevAngle
            // Unwrap to [-π, π] so we measure true incremental rotation, not jumps.
            while (delta > Math.PI)  delta -= 2.0 * Math.PI
            while (delta < -Math.PI) delta += 2.0 * Math.PI
            totalAngle += delta
            prevAngle = angle
        }
        val windingDegrees = Math.abs(Math.toDegrees(totalAngle)).toFloat()
        return windingDegrees >= SMART_LASSO_MIN_WINDING_DEGREES
    }

    private fun isScribbleCandidate(points: List<PointF>): Boolean {
        if (points.size < 4) return false
        var pathLength = 0f
        var minX = points[0].x; var minY = points[0].y
        var maxX = minX;        var maxY = minY
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLength += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (points[i].x < minX) minX = points[i].x else if (points[i].x > maxX) maxX = points[i].x
            if (points[i].y < minY) minY = points[i].y else if (points[i].y > maxY) maxY = points[i].y
        }
        val dw = maxX - minX; val dh = maxY - minY
        val diagonal = Math.sqrt((dw * dw + dh * dh).toDouble()).toFloat()
        if (diagonal < SCRIBBLE_MIN_DIAGONAL_DP * resources.displayMetrics.density) return false
        if (pathLength / diagonal < SCRIBBLE_DENSITY_RATIO) return false
        val filtered = mutableListOf(points[0])
        for (p in points) {
            val last = filtered.last()
            val dx = p.x - last.x; val dy = p.y - last.y
            if (dx * dx + dy * dy >= 4f) filtered.add(p)
        }
        if (filtered.size < 3) return false
        var reversals = 0
        for (i in 2 until filtered.size) {
            val ax = filtered[i - 1].x - filtered[i - 2].x
            val ay = filtered[i - 1].y - filtered[i - 2].y
            val bx = filtered[i].x - filtered[i - 1].x
            val by = filtered[i].y - filtered[i - 1].y
            if (ax * bx + ay * by < 0f) reversals++
        }
        return reversals >= SCRIBBLE_MIN_DIRECTION_REVERSALS
    }

    private fun scribbleHitTest(
        scribblePoints: List<PointF>,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        lineObjects: List<LineRender>,
        density: Float,
        links: List<LinkRender> = emptyList(),
        stickyNotes: List<StickyNoteRender> = emptyList(),
        shapes: List<ShapeRender> = emptyList(),
    ): List<String> {
        if (scribblePoints.size < 2) return emptyList()
        val touchRadiusPx = SCRIBBLE_STROKE_TOUCH_RADIUS_DP * density
        val touchRadiusSq = touchRadiusPx * touchRadiusPx
        val penetrationPx = SCRIBBLE_BBOX_PENETRATION_DP * density
        var sMinX = scribblePoints[0].x; var sMinY = scribblePoints[0].y
        var sMaxX = sMinX;               var sMaxY = sMinY
        for (sp in scribblePoints) {
            if (sp.x < sMinX) sMinX = sp.x else if (sp.x > sMaxX) sMaxX = sp.x
            if (sp.y < sMinY) sMinY = sp.y else if (sp.y > sMaxY) sMaxY = sp.y
        }
        val scribbleBounds = RectF(
            sMinX - touchRadiusPx, sMinY - touchRadiusPx,
            sMaxX + touchRadiusPx, sMaxY + touchRadiusPx,
        )
        val rawBounds = RectF(sMinX, sMinY, sMaxX, sMaxY)
        val hitIds = mutableListOf<String>()
        for (stroke in strokes) {
            if (!RectF.intersects(scribbleBounds, stroke.boundingBox)) continue
            var hit = false
            outer@ for (sp in stroke.points) {
                for (i in 1 until scribblePoints.size) {
                    if (pointToSegmentDistSq(sp, scribblePoints[i - 1], scribblePoints[i]) <= touchRadiusSq) {
                        hit = true; break@outer
                    }
                }
            }
            if (hit) hitIds.add(stroke.id)
        }
        for (heading in headings) {
            if (!RectF.intersects(rawBounds, heading.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, heading.boundingBox) >= penetrationPx) {
                hitIds.add(heading.id)
            }
        }
        for (textObj in textObjects) {
            if (!RectF.intersects(rawBounds, textObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, textObj.boundingBox) >= penetrationPx) {
                hitIds.add(textObj.id)
            }
        }
        for (lineObj in lineObjects) {
            if (!RectF.intersects(rawBounds, lineObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, lineObj.boundingBox) >= penetrationPx) {
                hitIds.add(lineObj.id)
            }
        }
        for (link in links) {
            if (!RectF.intersects(rawBounds, link.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, link.boundingBox) >= penetrationPx) {
                hitIds.add(link.id)
            }
        }
        for (note in stickyNotes) {
            if (!RectF.intersects(rawBounds, note.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, note.boundingBox) >= penetrationPx) {
                hitIds.add(note.id)
            }
        }
        for (shape in shapes) {
            if (!RectF.intersects(rawBounds, shape.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, shape.boundingBox) >= penetrationPx) {
                hitIds.add(shape.id)
            }
        }
        return hitIds
    }

    private fun scribblePathPenetration(points: List<PointF>, box: RectF): Float {
        var total = 0f
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            if (box.contains(p1.x, p1.y) || box.contains(p2.x, p2.y)) {
                val dx = p2.x - p1.x; val dy = p2.y - p1.y
                total += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        }
        return total
    }

    // ── NotebookView interface ────────────────────────────────────────────────

    override fun asView(): View = this

    override fun setToolbarExclusion(rect: Rect?) {
        toolbarExclusion = rect?.takeUnless { it.isEmpty }?.let { Rect(it) }
        // Apply live unless a full-screen disable owns the areas — leaving a suppressed
        // mode re-applies via applyToolToFirmware anyway.
        if (firmware && !firmwareSuppressed) applyDisableAreas()
    }

    // Host-driven input gating (dialog up, non-drawing view in a multi-view host). Both
    // touch process-global firmware state, so they only act while this view owns it.
    override fun enableDrawing() {
        if (!firmware || inkOwner !== this) return
        applyToolToFirmware()   // restores disable areas + the armed tool's pen state
    }

    override fun disableDrawing() {
        if (!firmware || inkOwner !== this) return
        // Bake first: hosts that switch views inside one window (day window Note→Events)
        // cross no focus boundary, so pending overlay ink would float above the new view.
        releaseFirmwareOverlay()
        SupernoteInk.setFullScreenDisable(width, height)
    }

    // Every host calls this from onResume. Focus events are unreliable on e-ink (Onyx
    // measured; assume the same here), and while we were away the firmware handed the pen
    // to other apps and reset full-UI ink — so re-assert the WHOLE setup, not a partial
    // re-enable. This is also the reclaim after a translucent overlay host: our onResume
    // runs before its onDestroy, so claiming here flips [inkOwner] back to us and defuses
    // that host's late focus-loss/detach teardown.
    override fun resumeDrawing() {
        if (width > 0 && height > 0) setupFirmwareInk()
        // else not laid out yet — onSizeChanged runs the setup after first layout.
    }

    // Fired by the hosts on every toolbar touch — the Ratta analogue of Onyx releasing its
    // EPD overlay: bake pending firmware ink and clear the overlay so chrome paints clean.
    override fun releaseRender() {
        releaseFirmwareOverlay()
    }

    // Before launching (and finishing into) another drawing screen: hand off cleanly and
    // stop painting full-UI ink — the successor's own setup re-claims and re-enables.
    override fun releaseForHandoff() {
        if (!firmware || inkOwner !== this) return
        releaseFirmwareOverlay()
        SupernoteInk.enableFullUiAuto(context, false)
        // inkOwner stays ours: if the successor never claims (edge), our detach cleans up;
        // when it does claim, setupFirmwareInk overwrites the token and our teardowns skip.
    }

    override fun resetOverlay() {
        if (!firmware) return
        releaseFirmwareOverlay()
        setupFirmwareInk()
    }

    override fun setTextPlacementMode(active: Boolean) {
        isTextPlacementMode = active
        firmwareToolBoundary()
    }

    override fun setDragMoveMode(enabled: Boolean) {
        if (!enabled && isDragMoveActive) {
            dragBackingBitmap?.recycle(); dragBackingBitmap = null
            isDragMoveActive = false; dragThresholdMet = false
            dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList()
            dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
            dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
            dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
            dragOriginalShapeObjects = emptyList()
            // The abandoned drag full-screen-disabled at entry — restore the armed tool.
            if (firmware) applyToolToFirmware()
            invalidate()
        }
    }

    override fun setLassoMode(active: Boolean) {
        isLassoMode = active
        // Cancel a live drag BEFORE the tool boundary, or applyToolToFirmware would still
        // see isDragMoveActive and push a stale full-screen disable.
        if (!active && isDragMoveActive) setDragMoveMode(false)
        firmwareToolBoundary()
        if (!active) {
            lassoOverlayPath = null
            lassoSelectionBox = null
            lassoGestureStartPoint = null
            lassoGesturePath = null
            invalidate()
        }
    }

    override fun setLassoEraserMode(active: Boolean) {
        isLassoEraserMode = active
        firmwareToolBoundary()
        if (!active) {
            lassoOverlayPath       = null
            lassoEraserDisplayPath = null
            lassoGestureStartPoint = null
            lassoGesturePath       = null
            invalidate()
        }
    }

    // ── Shape transform mode ─────────────────────────────────────────────────

    override fun enterShapeTransform(render: ShapeRender) {
        transformBeforeRender = render
        transformController.attach(render)
        isShapeTransformMode = true
        firmwareToolBoundary()
        invalidate()
    }

    override fun exitShapeTransform() {
        val before = transformBeforeRender ?: return
        val after  = transformController.getWorkingRender() ?: before
        onShapeTransformed?.invoke(before, after)
        transformBeforeRender = null
        isShapeTransformMode  = false
        firmwareToolBoundary()
        invalidate()
    }

    private fun handleShapeTransformTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grab = transformController.onDown(event.x, event.y)
                if (grab == ShapeTransformController.Grab.NONE) {
                    exitShapeTransform()
                    post { onShapeTransformTapOutside?.invoke() }
                } else {
                    onShapeTransformDragStarted?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                transformController.onMove(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                transformController.onUp()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    transformController.getWorkingRender()?.let {
                        onShapeTransformMoved?.invoke(it.boundingBox)
                    }
                }
                invalidate()
            }
        }
        return true
    }

    override fun toggleShapeAspectLock(): ShapeRender? {
        if (!isShapeTransformMode) return null
        val updated = transformController.toggleAspectLock()
        if (updated != null) invalidate()
        return updated
    }

    override fun getShapeTransformWorkingRender(): ShapeRender? =
        if (isShapeTransformMode) transformController.getWorkingRender() else null

    // ── Lasso eraser ─────────────────────────────────────────────────────────

    private fun handleLassoEraserTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        val tapThresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoEraserDisplayPath = null
                invalidate()
                lassoGesturePath = Path().also { it.moveTo(event.x, event.y) }
                // Firmware path (Phase 5): the panel paints the x-stream trail (LASSO_X);
                // the jittered software display path is the non-firmware stand-in only.
                if (!firmware) lassoEraserDisplayPath = Path().also { it.moveTo(event.x, event.y) }
                lassoGestureStartPoint = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val path = lassoGesturePath ?: return true
                val display = lassoEraserDisplayPath
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    path.lineTo(hx, hy)
                    display?.lineTo(hx + jitter(), hy + jitter())
                }
                path.lineTo(event.x, event.y)
                display?.lineTo(event.x + jitter(), event.y + jitter())
                if (!firmware) {
                    val now = System.currentTimeMillis()
                    if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                        lastLassoRefreshMs = now
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val path  = lassoGesturePath       ?: return true
                val start = lassoGestureStartPoint ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                lassoGesturePath       = null
                lassoGestureStartPoint = null
                lassoEraserDisplayPath = null
                invalidate()
                // Wipe the firmware x-stream trail; the erase itself (async hit test →
                // onLassoEraseComplete → host redraw) co-presents further app frames.
                if (firmware) releaseGestureTrace()
                // Classify by gesture extent, not net displacement, so a small circular erase
                // gesture (returns near its origin) is not mistaken for a tap.
                val gestureBounds = RectF()
                path.computeBounds(gestureBounds, true)
                if (gestureBounds.width() >= tapThresholdPx || gestureBounds.height() >= tapThresholdPx) {
                    performLassoErase(path, start)
                }
                // Tap: clear overlay, stay in lasso eraser mode (no further action needed).
            }
        }
        return true
    }

    private fun performLassoErase(drawnPath: Path, startPoint: PointF) {
        drawnPath.lineTo(startPoint.x, startPoint.y)
        drawnPath.close()
        val strokeSnapshot      = strokes.toList()
        val headingSnapshot     = headings.toList()
        val textSnapshot        = textObjects.toList()
        val lineSnapshot        = lineObjects.toList()
        val linkSnapshot        = links.toList()
        val stickyNoteSnapshot  = stickyNotes.toList()
        val shapeSnapshot       = shapeObjects.toList()
        Thread {
            val hitIds = runLassoHitTest(drawnPath, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot, linkSnapshot, stickyNoteSnapshot, shapeSnapshot)
            post {
                lassoOverlayPath       = null
                lassoEraserDisplayPath = null
                invalidate()
                if (hitIds.isNotEmpty()) {
                    onLassoEraseComplete?.invoke(hitIds)
                }
            }
        }.start()
    }

    private fun runLassoHitTest(
        path: Path,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke> = emptyList(),
        textObjects: List<TextRender> = emptyList(),
        lineObjects: List<LineRender> = emptyList(),
        links: List<LinkRender> = emptyList(),
        stickyNotes: List<StickyNoteRender> = emptyList(),
        shapes: List<ShapeRender> = emptyList(),
    ): List<String> {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 10f || bounds.height() < 10f) return emptyList()
        val clipRect = android.graphics.Rect(
            (bounds.left   - 1f).toInt().coerceAtLeast(0),
            (bounds.top    - 1f).toInt().coerceAtLeast(0),
            (bounds.right  + 1f).toInt(),
            (bounds.bottom + 1f).toInt(),
        )
        val region = Region()
        region.setPath(path, Region(clipRect))
        val hitIds = mutableListOf<String>()
        for (stroke in strokes) {
            if (!RectF.intersects(bounds, stroke.boundingBox)) continue
            for (pt in stroke.points) {
                if (region.contains(pt.x.toInt(), pt.y.toInt())) {
                    hitIds.add(stroke.id)
                    break
                }
            }
        }
        // Heading / text / line hit-test: select if the lasso overlaps any part of the object's
        // bounding box (touch semantics, matching strokes) — not just its center point.
        for (heading in headings) {
            if (!RectF.intersects(bounds, heading.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, heading.boundingBox)) {
                hitIds.add(heading.id)
            }
        }
        for (textObj in textObjects) {
            if (!RectF.intersects(bounds, textObj.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, textObj.boundingBox)) {
                hitIds.add(textObj.id)
            }
        }
        for (lineObj in lineObjects) {
            if (!RectF.intersects(bounds, lineObj.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, lineObj.boundingBox)) {
                hitIds.add(lineObj.id)
            }
        }
        for (link in links) {
            if (!RectF.intersects(bounds, link.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, link.boundingBox)) {
                hitIds.add(link.id)
            }
        }
        for (note in stickyNotes) {
            if (!RectF.intersects(bounds, note.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, note.boundingBox)) {
                hitIds.add(note.id)
            }
        }
        for (shape in shapes) {
            if (!RectF.intersects(bounds, shape.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, shape.boundingBox)) {
                hitIds.add(shape.id)
            }
        }
        return hitIds
    }

    override fun setLassoSelectedIds(ids: Set<String>, box: RectF) {
        lassoSelectedIds = ids
        setLassoOverlay(null, box)
    }

    override fun setLassoOverlay(path: Path?, selectionBox: RectF?) {
        lassoOverlayPath = path
        lassoSelectionBox = selectionBox
        invalidate()
    }

    override fun setEraserMode(active: Boolean) {
        isEraserActive = active
        firmwareToolBoundary()
    }

    /**
     * Set the template bitmap to use as the page background.
     * Null = plain white. Redraws the canvas immediately (strokes on top of new template).
     */
    override fun setTemplate(bitmap: Bitmap?) {
        // Boundary: bake pending overlay ink before the template swap re-records the page.
        releaseFirmwareOverlay()
        templateBitmap = bitmap
        redrawCanvas()
    }

    override fun eraseAll() {
        // Pending strokes are being erased with everything else — nothing left to bake.
        pendingBake = false
        activePoints.clear()
        strokes.clear()
        headings = emptyList()
        textObjects = emptyList()
        lineObjects = emptyList()
        shapeObjects = emptyList()
        links = emptyList()
        stickyNotes = emptyList()
        // Re-record the (now empty) committed node: white → template only. redrawCanvas invalidates.
        redrawCanvas()
        if (firmware) SupernoteInk.clearAll()
    }

    /**
     * Page-navigation clear: bake + release the firmware overlay FIRST (or the old page's
     * live ink survives onto the new page), then drop the in-memory content WITHOUT
     * re-recording — the outgoing page stays on the panel until [loadStrokesWithBitmap]
     * swaps in the new page with a single refresh (no white double-flash on e-ink).
     */
    override fun clearForPageLoad() {
        releaseFirmwareOverlay()
        activePoints.clear()
        strokes.clear()
        headings = emptyList()
        textObjects = emptyList()
        lineObjects = emptyList()
        shapeObjects = emptyList()
        links = emptyList()
        stickyNotes = emptyList()
    }

    override fun loadHeadings(headings: List<HeadingStroke>) {
        this.headings = headings
    }

    override fun getHeadings(): List<HeadingStroke> = headings

    override fun loadTextObjects(textObjects: List<TextRender>) {
        this.textObjects = textObjects
    }

    override fun getTextObjects(): List<TextRender> = textObjects

    override fun loadLineObjects(lineObjects: List<LineRender>) {
        this.lineObjects = lineObjects
    }

    override fun getLineObjects(): List<LineRender> = lineObjects

    override fun loadLinks(links: List<LinkRender>) {
        this.links = links
    }

    override fun getLinks(): List<LinkRender> = links

    override fun loadStickyNotes(stickyNotes: List<StickyNoteRender>) {
        this.stickyNotes = stickyNotes
        redrawCanvas()
        invalidate()
    }

    override fun getStickyNotes(): List<StickyNoteRender> = stickyNotes

    override fun loadShapeObjects(shapeObjects: List<ShapeRender>) {
        this.shapeObjects = shapeObjects
    }

    override fun getShapeObjects(): List<ShapeRender> = shapeObjects

    override fun loadStrokes(strokes: List<LiveStroke>) {
        // Boundary: bake + clear before the content swap so no old overlay ink survives.
        releaseFirmwareOverlay()
        this.strokes.clear()
        this.strokes.addAll(strokes)
        redrawCanvas()
    }

    override fun getStrokes(): List<LiveStroke> = strokes.toList()

    // ── Option B: off-thread bitmap pre-build ─────────────────────────────────

    /** Build the render bitmap on a background thread. Safe to call from Dispatchers.IO. */
    override fun buildRenderBitmap(
        strokes: List<LiveStroke>,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>?,
        lineObjects: List<LineRender>?,
        links: List<LinkRender>?,
        stickyNotes: List<StickyNoteRender>?,
        shapeObjects: List<ShapeRender>?,
    ): Bitmap? {
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        // null = fall back to stored field (undo/redo paths); non-null = explicit list (page load)
        val effectiveTextObjects = textObjects ?: this.textObjects
        val effectiveLineObjects = lineObjects ?: this.lineObjects
        val effectiveLinks = links ?: this.links
        val effectiveStickyNotes = stickyNotes ?: this.stickyNotes
        val effectiveShapeObjects = shapeObjects ?: this.shapeObjects
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        for (heading in headings) {
            if (heading.recognizedText != null) {
                drawHeadingText(canvas, heading)
            } else {
                for (liveStroke in heading.strokes) drawStrokePath(canvas, liveStroke)
            }
        }
        for (textObj in effectiveTextObjects) {
            drawTextObject(canvas, textObj, w)
        }
        for (lineObj in effectiveLineObjects) {
            drawLineObject(canvas, lineObj)
        }
        for (shape in effectiveShapeObjects) {
            drawShapeObject(canvas, shape)
        }
        for (link in effectiveLinks) {
            drawLinkObject(canvas, link, w)
        }
        for (note in effectiveStickyNotes) {
            drawStickyNoteObject(canvas, note)
        }
        for (liveStroke in strokes) drawStrokePath(canvas, liveStroke)
        return bmp
    }

    /** Load the page's content and record the committed node. Any off-thread-built [bitmap] is not
     *  needed for display in the GPU path — released. */
    override fun loadStrokesWithBitmap(
        strokes: List<LiveStroke>,
        bitmap: Bitmap?,
        templateBitmap: Bitmap?,
    ) {
        // Boundary: bake + clear before the content swap so no old overlay ink survives.
        // Usually a no-op bake — clearForPageLoad already drained pending on page nav.
        releaseFirmwareOverlay()
        this.strokes.clear()
        this.strokes.addAll(strokes)
        this.templateBitmap = templateBitmap
        bitmap?.recycle()
        redrawCanvas()
    }


    /**
     * Capture the current strokes and heading backgrounds as a base64-encoded PNG with a
     * TRANSPARENT background.  The template is intentionally excluded — at render time
     * the stack is: template → snapshot PNG → new strokes drawn this session.
     * Returns null if there are no strokes and no headings, or the view is not yet laid out.
     */
    override fun captureSnapshot(): String? {
        // Boundary: bake + clear FIRST so the panel matches the snapshot. The snapshot's
        // data is already correct either way (it renders from [strokes], pending included).
        releaseFirmwareOverlay()
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return null
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Complete cover image: white base + template + content. The snapshot's only consumer is the
        // library-grid cover, shown directly (no compositing over a template at load), so it must
        // paint the full page like the page-index/PDF render does.
        val snapshotCanvas = Canvas(bmp)
        snapshotCanvas.drawColor(Color.WHITE)
        templateBitmap?.let { tb ->
            snapshotCanvas.drawBitmap(tb, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        }
        for (heading in headings) {
            if (heading.recognizedText != null) {
                drawHeadingText(snapshotCanvas, heading)
            } else {
                for (liveStroke in heading.strokes) drawStrokePath(snapshotCanvas, liveStroke)
            }
        }
        for (textObj in textObjects) {
            drawTextObject(snapshotCanvas, textObj, w)
        }
        for (lineObj in lineObjects) {
            drawLineObject(snapshotCanvas, lineObj)
        }
        for (link in links) {
            drawLinkObject(snapshotCanvas, link, w)
        }
        for (note in stickyNotes) {
            drawStickyNoteObject(snapshotCanvas, note)
        }
        for (liveStroke in strokes) drawStrokePath(snapshotCanvas, liveStroke)
        val b64 = ImageCodec.encodeBase64(bmp)
        bmp.recycle()
        return b64
    }

    /**
     * Silently update the in-memory stroke list without triggering any canvas redraw.
     * Called after a snapshot fast-load so stroke data is available for erasing and
     * export while the snapshot composite is already displayed on screen.
     */
    override fun setStrokeListSilently(strokes: List<LiveStroke>) {
        this.strokes.clear()
        this.strokes.addAll(strokes)
        // No redraw — the snapshot composite bitmap already shows the correct visual state.
    }

    override fun releaseResources() {
        // Host onDestroy. If we still own the firmware — leaving the drawing world for a
        // non-drawing screen (library, Today) or leaving the app — release it fully so
        // nothing stays claimed. The detach callback that follows would repeat this;
        // dropping inkOwner here makes it a no-op instead.
        if (firmware && inkOwner === this) {
            releaseFirmwareOverlay()
            SupernoteInk.setFullScreenDisable(width, height)
            SupernoteInk.enableFullUiAuto(context, false)
            inkOwner = null
        }
        committedNode.discardDisplayList()
        dragBackingBitmap?.recycle()
        dragBackingBitmap = null
    }
}
