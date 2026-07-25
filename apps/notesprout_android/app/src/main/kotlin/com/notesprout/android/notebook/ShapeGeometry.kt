package com.notesprout.android.notebook

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.ShapeType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object ShapeGeometry {

    /**
     * Build the outline Path for [r] in absolute (page/canvas) coordinates.
     * Pure function — no view state; safe to call from exporter or any thread.
     */
    fun pathFor(r: ShapeRender): Path {
        val cx = r.centerX
        val cy = r.centerY
        val hw = r.width / 2f
        val hh = r.height / 2f
        val L = cx - hw
        val T = cy - hh
        val R = cx + hw
        val B = cy + hh

        val path = Path()

        when (r.type) {
            ShapeType.RECTANGLE -> {
                path.moveTo(L, T)
                path.lineTo(R, T)
                path.lineTo(R, B)
                path.lineTo(L, B)
                path.close()
            }

            ShapeType.ELLIPSE -> {
                path.addOval(RectF(L, T, R, B), Path.Direction.CW)
            }

            ShapeType.TRIANGLE -> {
                path.moveTo(cx, T)
                path.lineTo(R, B)
                path.lineTo(L, B)
                path.close()
            }

            ShapeType.DIAMOND -> {
                path.moveTo(cx, T)
                path.lineTo(R, cy)
                path.lineTo(cx, B)
                path.lineTo(L, cy)
                path.close()
            }

            ShapeType.TRAPEZOID -> {
                val topInset = 0.2f * r.width
                path.moveTo(L + topInset, T)
                path.lineTo(R - topInset, T)
                path.lineTo(R, B)
                path.lineTo(L, B)
                path.close()
            }

            ShapeType.PENTAGON -> {
                val startAngle = (-Math.PI / 2).toFloat()
                val n = 5
                for (i in 0 until n) {
                    val theta = startAngle + i * 2f * Math.PI.toFloat() / n
                    val x = cx + hw * cos(theta)
                    val y = cy + hh * sin(theta)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }

            ShapeType.HEXAGON -> {
                // flat top: start angle = -π/2 + π/6 so one edge is horizontal at top
                val startAngle = (-Math.PI / 2 + Math.PI / 6).toFloat()
                val n = 6
                for (i in 0 until n) {
                    val theta = startAngle + i * 2f * Math.PI.toFloat() / n
                    val x = cx + hw * cos(theta)
                    val y = cy + hh * sin(theta)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }

            ShapeType.STAR -> {
                // Pentagram: connect N outer tips in skip order (step=2), e.g. A→C→E→B→D→A.
                // This produces the visible crossing lines through the center that match the
                // hand-drawn skip-pattern stroke, rather than a clean 10-point star outline.
                val n = r.pointCount
                val startAngle = (-Math.PI / 2).toFloat()
                val angleStep = 2f * Math.PI.toFloat() / n
                val tips = Array(n) { i ->
                    val theta = startAngle + i * angleStep
                    Pair(cx + hw * cos(theta), cy + hh * sin(theta))
                }
                for (i in 0 until n) {
                    val (x, y) = tips[(i * 2) % n]
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }

            ShapeType.ARCH -> {
                // Semicircle on top + straight sides + flat base.
                // If height >= half-width: semicircle takes top hh portion, straight sides fill rest.
                // If height < half-width: degenerate to pure semicircle.
                if (r.height >= hw) {
                    // straight base at B, straight sides from B up to mid (cy), dome from there up
                    path.moveTo(L, B)
                    path.lineTo(L, cy)
                    path.arcTo(RectF(L, T, R, T + r.height), 180f, 180f)
                    path.lineTo(R, B)
                    path.close()
                } else {
                    // degenerate: pure semicircle
                    path.addArc(RectF(L, T, R, B), 180f, 180f)
                    path.lineTo(L, cy)
                    path.close()
                }
            }

            ShapeType.LINE -> {
                path.moveTo(L, cy)
                path.lineTo(R, cy)
            }

            ShapeType.ARROW -> {
                path.moveTo(L, cy)
                path.lineTo(R, cy)
                // Arrowhead at (R, cy): two segments back at ~±150° from the shaft
                val headLen = min(hw * 0.5f, 24f * 3f) // rough px estimate; ShapeGeometry is density-agnostic
                val angle1 = (Math.PI * 5 / 6).toFloat()  // 150°
                val angle2 = (-Math.PI * 5 / 6).toFloat() // -150° (210°)
                path.moveTo(R, cy)
                path.lineTo(R + headLen * cos(angle1), cy + headLen * sin(angle1))
                path.moveTo(R, cy)
                path.lineTo(R + headLen * cos(angle2), cy + headLen * sin(angle2))
            }
        }

        if (r.rotationDeg != 0f) {
            val matrix = Matrix()
            matrix.setRotate(r.rotationDeg, cx, cy)
            path.transform(matrix)
        }

        return path
    }
}
