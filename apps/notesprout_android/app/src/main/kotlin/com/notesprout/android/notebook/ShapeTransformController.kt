package com.notesprout.android.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.ShapeType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * View-agnostic controller for shape transform mode (resize, rotate, aspect toggle).
 *
 * Lifecycle: [attach] on enter, [getWorkingRender] to read current state during drag/toggle,
 * [getBeforeRender] for the undo snapshot, then call the activity's `onShapeTransformed` callback
 * before discarding. The drawing view calls [draw] in its overlay layer and routes stylus
 * touch events through [onDown]/[onMove]/[onUp].
 */
class ShapeTransformController(private val density: Float) {

    enum class Grab {
        NONE, BODY, ROTATE,
        TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT
    }

    private var beforeRender: ShapeRender? = null
    private var workingRender: ShapeRender? = null

    private var activeGrab = Grab.NONE
    // ROTATE grab state
    private var rotateGrabOffsetDeg = 0f
    // BODY drag state
    private var bodyGrabLocalX = 0f
    private var bodyGrabLocalY = 0f
    // RESIZE state
    private var anchorX = 0f
    private var anchorY = 0f
    private var grabW = 0f
    private var grabH = 0f
    private var grabAspectRatio = 1f

    private val handleSizePx get() = SHAPE_HANDLE_SIZE_DP * density
    private val handleTouchPx get() = SHAPE_HANDLE_TOUCH_DP * density
    private val rotateOffsetPx get() = SHAPE_ROTATE_OFFSET_DP * density
    private val minSizePx get() = SHAPE_MIN_SIZE_DP * density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = density
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = density
    }

    fun attach(render: ShapeRender) {
        beforeRender = render
        workingRender = render
        activeGrab = Grab.NONE
    }

    fun getBeforeRender(): ShapeRender? = beforeRender
    fun getWorkingRender(): ShapeRender? = workingRender

    fun draw(canvas: Canvas) {
        val r = workingRender ?: return
        val (corners, midpoints, rotateKnob, topMid) = computeHandlePositions(r)

        // Dashed oriented box outline
        val boxPath = Path()
        boxPath.moveTo(corners[0].first, corners[0].second)
        for (i in 1 until 4) boxPath.lineTo(corners[i].first, corners[i].second)
        boxPath.close()
        canvas.drawPath(boxPath, boxPaint)

        // Connector line from top-center handle to rotate knob
        canvas.drawLine(topMid.first, topMid.second, rotateKnob.first, rotateKnob.second, connectorPaint)

        // 8 handles: filled black squares with black border (4 corners + 4 edge midpoints)
        val hs = handleSizePx / 2f
        val handles = corners + midpoints
        for ((hx, hy) in handles) {
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handleFillPaint)
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handleBorderPaint)
        }

        // Rotate knob: filled black circle
        canvas.drawCircle(rotateKnob.first, rotateKnob.second, handleSizePx / 2f, handleFillPaint)
        canvas.drawCircle(rotateKnob.first, rotateKnob.second, handleSizePx / 2f, handleBorderPaint)
    }

    fun hitTest(x: Float, y: Float): Grab {
        val r = workingRender ?: return Grab.NONE
        val (corners, midpoints, rotateKnob, _) = computeHandlePositions(r)

        fun distSq(p: Pair<Float, Float>) = (x - p.first).pow(2) + (y - p.second).pow(2)

        // On small shapes the 8 handles collectively cover most of the body.
        // Clamp the touch radius so no single handle claims more than 1/3 of the
        // smallest dimension, and guarantee at least the visual handle size.
        val minDim = minOf(r.width, r.height)
        val effectiveTouchPx = minOf(handleTouchPx, minDim / 3f).coerceAtLeast(handleSizePx)
        val touchRadSq = effectiveTouchPx * effectiveTouchPx

        // Body-priority zone: touches very close to the center always resolve to BODY
        // so the user can drag small shapes without accidentally hitting a handle.
        val centerDistSq = (x - r.centerX).pow(2) + (y - r.centerY).pow(2)
        val bodyZoneRadius = minDim / 4f
        if (centerDistSq <= bodyZoneRadius * bodyZoneRadius && isInOrientedBox(x, y, r)) {
            return Grab.BODY
        }

        if (distSq(rotateKnob) <= touchRadSq) return Grab.ROTATE

        // corners[0..3] = TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT
        val cornerGrabs = listOf(Grab.TOP_LEFT, Grab.TOP_RIGHT, Grab.BOTTOM_RIGHT, Grab.BOTTOM_LEFT)
        for ((i, grab) in cornerGrabs.withIndex()) {
            if (distSq(corners[i]) <= touchRadSq) return grab
        }

        // midpoints[0..3] = TOP, RIGHT, BOTTOM, LEFT
        val midGrabs = listOf(Grab.TOP, Grab.RIGHT, Grab.BOTTOM, Grab.LEFT)
        for ((i, grab) in midGrabs.withIndex()) {
            if (distSq(midpoints[i]) <= touchRadSq) return grab
        }

        if (isInOrientedBox(x, y, r)) return Grab.BODY

        return Grab.NONE
    }

    fun onDown(x: Float, y: Float): Grab {
        val r = workingRender ?: return Grab.NONE
        activeGrab = hitTest(x, y)

        val hw = r.width / 2f
        val hh = r.height / 2f
        val rad = Math.toRadians(r.rotationDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        fun toWorld(lx: Float, ly: Float) = Pair(
            r.centerX + lx * cosA - ly * sinA,
            r.centerY + lx * sinA + ly * cosA,
        )

        when (activeGrab) {
            Grab.ROTATE -> {
                val angle = Math.toDegrees(
                    atan2((y - r.centerY).toDouble(), (x - r.centerX).toDouble())
                ).toFloat()
                rotateGrabOffsetDeg = angle - r.rotationDeg
            }
            Grab.BODY -> {
                bodyGrabLocalX = x - r.centerX
                bodyGrabLocalY = y - r.centerY
            }
            Grab.NONE -> {}
            else -> {
                // Record fixed anchor (opposite corner/edge) in world space
                val (ax, ay) = when (activeGrab) {
                    Grab.TOP_LEFT     -> toWorld(hw, hh)
                    Grab.TOP          -> toWorld(0f, hh)
                    Grab.TOP_RIGHT    -> toWorld(-hw, hh)
                    Grab.RIGHT        -> toWorld(-hw, 0f)
                    Grab.BOTTOM_RIGHT -> toWorld(-hw, -hh)
                    Grab.BOTTOM       -> toWorld(0f, -hh)
                    Grab.BOTTOM_LEFT  -> toWorld(hw, -hh)
                    Grab.LEFT         -> toWorld(hw, 0f)
                    else              -> toWorld(0f, 0f)
                }
                anchorX = ax
                anchorY = ay
                grabW = r.width
                grabH = r.height
                grabAspectRatio = if (r.height > 0f) r.width / r.height else 1f
            }
        }
        return activeGrab
    }

    fun onMove(x: Float, y: Float) {
        val r = workingRender ?: return
        when (activeGrab) {
            Grab.NONE -> {}

            Grab.ROTATE -> {
                val angle = Math.toDegrees(
                    atan2((y - r.centerY).toDouble(), (x - r.centerX).toDouble())
                ).toFloat()
                var newRot = angle - rotateGrabOffsetDeg
                while (newRot > 180f) newRot -= 360f
                while (newRot <= -180f) newRot += 360f
                workingRender = rebuildBounds(r.copy(rotationDeg = snapRotation(newRot)))
            }

            Grab.BODY -> {
                workingRender = rebuildBounds(r.copy(
                    centerX = x - bodyGrabLocalX,
                    centerY = y - bodyGrabLocalY,
                ))
            }

            else -> {
                val rad = Math.toRadians(r.rotationDeg.toDouble())
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()

                // Vector from anchor to pointer in local (un-rotated) frame
                val dx = x - anchorX
                val dy = y - anchorY
                val lx = dx * cosA + dy * sinA
                val ly = -dx * sinA + dy * cosA

                // New dimensions
                var newW = r.width
                var newH = r.height

                when (activeGrab) {
                    Grab.TOP_LEFT, Grab.TOP_RIGHT, Grab.BOTTOM_RIGHT, Grab.BOTTOM_LEFT -> {
                        val rawW = abs(lx).coerceAtLeast(minSizePx)
                        val rawH = abs(ly).coerceAtLeast(minSizePx)
                        if (r.aspectLocked) {
                            val scale = max(rawW / grabW, rawH / grabH)
                            newW = (grabW * scale).coerceAtLeast(minSizePx)
                            newH = (grabH * scale).coerceAtLeast(minSizePx)
                        } else {
                            newW = rawW
                            newH = rawH
                        }
                    }
                    Grab.TOP, Grab.BOTTOM -> {
                        newH = abs(ly).coerceAtLeast(minSizePx)
                        if (r.aspectLocked) newW = (newH * grabAspectRatio).coerceAtLeast(minSizePx)
                    }
                    Grab.LEFT, Grab.RIGHT -> {
                        newW = abs(lx).coerceAtLeast(minSizePx)
                        if (r.aspectLocked) newH = (newW / grabAspectRatio).coerceAtLeast(minSizePx)
                    }
                    else -> {}
                }

                // Half-extents in local frame (signed, pointing from anchor toward moved point)
                val halfExtLx = when (activeGrab) {
                    Grab.TOP_LEFT, Grab.BOTTOM_LEFT, Grab.LEFT -> -newW / 2f
                    Grab.TOP_RIGHT, Grab.BOTTOM_RIGHT, Grab.RIGHT -> newW / 2f
                    else -> 0f
                }
                val halfExtLy = when (activeGrab) {
                    Grab.TOP_LEFT, Grab.TOP, Grab.TOP_RIGHT -> -newH / 2f
                    Grab.BOTTOM_LEFT, Grab.BOTTOM, Grab.BOTTOM_RIGHT -> newH / 2f
                    else -> 0f
                }

                // New center = anchor + rotate(halfExtent) back to world
                val newCx = anchorX + halfExtLx * cosA - halfExtLy * sinA
                val newCy = anchorY + halfExtLx * sinA + halfExtLy * cosA

                workingRender = rebuildBounds(r.copy(
                    centerX = newCx,
                    centerY = newCy,
                    width   = newW,
                    height  = newH,
                ))
            }
        }
    }

    fun onUp() {
        // Drag is committed live; nothing to finalize here.
    }

    fun toggleAspectLock(): ShapeRender? {
        val r = workingRender ?: return null
        val newLocked = !r.aspectLocked
        var newW = r.width
        var newH = r.height
        if (newLocked && r.type == ShapeType.ELLIPSE) {
            val s = max(r.width, r.height)
            newW = s; newH = s
        }
        workingRender = rebuildBounds(r.copy(aspectLocked = newLocked, width = newW, height = newH))
        return workingRender
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private data class HandlePositions(
        val corners: List<Pair<Float, Float>>,    // [TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT]
        val midpoints: List<Pair<Float, Float>>,  // [TOP, RIGHT, BOTTOM, LEFT]
        val rotateKnob: Pair<Float, Float>,
        val topMid: Pair<Float, Float>,
    )

    private fun computeHandlePositions(r: ShapeRender): HandlePositions {
        val hw = r.width / 2f
        val hh = r.height / 2f
        val rad = Math.toRadians(r.rotationDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        fun rotPt(lx: Float, ly: Float) = Pair(
            r.centerX + lx * cosA - ly * sinA,
            r.centerY + lx * sinA + ly * cosA,
        )

        val corners = listOf(
            rotPt(-hw, -hh), // TOP_LEFT
            rotPt(hw, -hh),  // TOP_RIGHT
            rotPt(hw, hh),   // BOTTOM_RIGHT
            rotPt(-hw, hh),  // BOTTOM_LEFT
        )
        val midpoints = listOf(
            rotPt(0f, -hh),  // TOP
            rotPt(hw, 0f),   // RIGHT
            rotPt(0f, hh),   // BOTTOM
            rotPt(-hw, 0f),  // LEFT
        )
        val topMid = midpoints[0]
        // Rotate-knob: offset outward from the top-mid handle (direction = center→topMid = (sinA,-cosA)).
        val rotateKnob = Pair(
            topMid.first + sinA * rotateOffsetPx,
            topMid.second - cosA * rotateOffsetPx,
        )
        return HandlePositions(corners, midpoints, rotateKnob, topMid)
    }

    private fun isInOrientedBox(x: Float, y: Float, r: ShapeRender): Boolean {
        val dx = x - r.centerX
        val dy = y - r.centerY
        val rad = Math.toRadians(r.rotationDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val lx = dx * cosA + dy * sinA
        val ly = -dx * sinA + dy * cosA
        return abs(lx) <= r.width / 2f && abs(ly) <= r.height / 2f
    }

    private fun snapRotation(deg: Float): Float {
        val snaps = listOf(-180f, -90f, 0f, 90f, 180f)
        val nearest = snaps.minByOrNull { abs(deg - it) } ?: deg
        return if (abs(deg - nearest) <= SHAPE_ROTATE_SNAP_DEG) nearest else deg
    }

    private fun rebuildBounds(r: ShapeRender): ShapeRender {
        val inflateBy = max(r.strokeWidthPx / 2f, 4f * density)
        val pathBounds = RectF()
        ShapeGeometry.pathFor(r).computeBounds(pathBounds, true)
        pathBounds.inset(-inflateBy, -inflateBy)
        return r.copy(boundingBox = pathBounds)
    }
}
