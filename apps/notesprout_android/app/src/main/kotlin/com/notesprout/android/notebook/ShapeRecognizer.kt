package com.notesprout.android.notebook

import android.graphics.PointF
import android.graphics.RectF
import com.notesprout.android.data.ShapeType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object ShapeRecognizer {

    data class Result(
        val type: ShapeType,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val rotationDeg: Float,
        val aspectLocked: Boolean,
        val pointCount: Int = 5,
    )

    fun recognize(points: List<PointF>, density: Float): Result? {
        if (points.size < 4) return null

        val bbox = boundingBox(points)
        val diag = sqrt((bbox.width() * bbox.width() + bbox.height() * bbox.height()).toDouble()).toFloat()
        if (diag < SHAPE_MIN_DIAGONAL_DP * density) return null

        val resampled = resample(points, 64)
        val first = resampled.first()
        val last  = resampled.last()
        val closedness = dist(first, last) / diag
        val isClosed = closedness <= SHAPE_CLOSURE_RATIO

        return if (!isClosed) {
            recognizeOpen(resampled, diag, bbox)
        } else {
            recognizeClosed(resampled, diag, bbox)
        }
    }

    // ── Open shape recognition ────────────────────────────────────────────────

    private fun recognizeOpen(pts: List<PointF>, diag: Float, bbox: RectF): Result? {
        val first = pts.first()
        val last  = pts.last()

        // Split: check straightness of the shaft (first 80%) and look for arrowhead in tail (last 20%)
        val shaftEndIdx = (pts.size * 0.80f).toInt().coerceIn(2, pts.size - 1)
        val shaft       = pts.subList(0, shaftEndIdx)
        val tail        = pts.subList(shaftEndIdx, pts.size)
        val shaftFirst  = shaft.first()
        val shaftLast   = shaft.last()
        val shaftLinLen = dist(shaftFirst, shaftLast)
        val shaftDev    = maxPerpendicularDeviation(shaft, shaftFirst, shaftLast)
        val shaftStraightness = if (shaftLinLen > 0f) shaftDev / shaftLinLen else Float.MAX_VALUE

        if (shaftStraightness <= SHAPE_LINE_STRAIGHTNESS) {
            // Shaft is straight. Check tail for arrowhead branches.
            // For shaft-first draws: arrowhead is drawn at the tail (after shaftLast),
            // so the arrow points in the direction first→last.
            if (tail.size >= 3 && hasArrowHead(tail, shaftFirst, shaftLast)) {
                return lineResult(ShapeType.ARROW, bbox, confidence = 0.75f, fromPt = first, toPt = last)
            }
            // Check overall stroke straightness for a plain line.
            val overallLen = dist(first, last)
            val overallDev = maxPerpendicularDeviation(pts, first, last)
            val overallStraightness = if (overallLen > 0f) overallDev / overallLen else Float.MAX_VALUE
            if (overallStraightness <= SHAPE_LINE_STRAIGHTNESS) {
                return lineResult(ShapeType.LINE, bbox, confidence = 0.85f, fromPt = first, toPt = last)
            }
            // Shaft straight but tip deviates — more likely pen wobble than arrowhead. Classify as LINE.
            return lineResult(ShapeType.LINE, bbox, confidence = 0.62f, fromPt = first, toPt = last)
        }

        // Check for an arch before RDP arrow: arches have no sharp corners so they won't
        // be caught by tryRdpArrow, but arrows' oscillating arrowhead sweep can exceed the
        // arch threshold — check arch first to avoid misclassifying arches as arrows.
        if (isArch(pts)) {
            return closedResult(ShapeType.ARCH, bbox, aspectLocked = false, confidence = 0.65f)
        }

        // RDP-based detection — compute once, try star then arrow.
        // Skip-pattern star: user draws a star by connecting outer tips in skip order
        // (A→C→E→B→D for a 5-pointed star), producing N all-sharp RDP corners = outer tips.
        val eps = SHAPE_RDP_EPS_RATIO * diag
        val rdpOpen = rdp(pts, eps)
        if (rdpOpen.size >= 4) {
            val openStarResult = tryRecognizeOpenStar(rdpOpen, bbox)
            if (openStarResult != null) return openStarResult
        }
        if (rdpOpen.size >= 3) {
            val rdpArrow = tryRdpArrow(rdpOpen, bbox)
            if (rdpArrow != null) return rdpArrow
        }

        return null
    }

    // Skip-pattern open star: N RDP corners = N outer tips, all with sharp interior angles.
    private fun tryRecognizeOpenStar(rdpPts: List<PointF>, bbox: RectF): Result? {
        val n = rdpPts.size
        if (n < 4) return null

        // Arrows have one dominant shaft segment (≥1.5× the next-longest); stars don't.
        // If the stroke has a clear shaft, let tryRdpArrow handle it instead.
        var maxSegLen = 0f; var secondSegLen = 0f
        for (i in 0 until n - 1) {
            val len = dist(rdpPts[i], rdpPts[i + 1])
            if (len > maxSegLen) { secondSegLen = maxSegLen; maxSegLen = len }
            else if (len > secondSegLen) secondSegLen = len
        }
        if (secondSegLen > 0f && maxSegLen >= secondSegLen * 1.5f) return null

        var sharpCount = 0; var wideCount = 0
        for (i in 1 until n - 1) {
            val a = interiorAngleDeg(rdpPts[i - 1], rdpPts[i], rdpPts[i + 1])
            if (a < 75f) sharpCount++ else if (a > 120f) wideCount++
        }
        if (sharpCount < 3 || wideCount > 1) return null

        val aspectLocked = isNearSquare(bbox)
        val w = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.width()
        val h = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.height()
        return Result(
            type = ShapeType.STAR,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = max(w, 1f),
            height = max(h, 1f),
            rotationDeg = 0f,
            aspectLocked = aspectLocked,
            pointCount = n,
        )
    }

    // Closed skip-pattern star: path returned near its start (closure ≤ 0.35), so it enters
    // recognizeClosed. RDP deduplication leaves N outer-tip corners arranged circularly.
    // All interior angles in a star's skip-pattern corners are very sharp (≈36° for a regular
    // pentagram); a true pentagon/hexagon has obtuse angles (≈108°/120°) so it won't pass.
    private fun tryRecognizeClosedStar(corners: List<PointF>, bbox: RectF): Result? {
        val n = corners.size
        if (n < 4 || n > 12) return null
        var sharpCount = 0; var wideCount = 0
        for (i in corners.indices) {
            val a = interiorAngleDeg(
                corners[(i - 1 + n) % n],
                corners[i],
                corners[(i + 1) % n],
            )
            if (a < 75f) sharpCount++ else if (a > 120f) wideCount++
        }
        if (sharpCount < 3 || wideCount > 1) return null

        val aspectLocked = isNearSquare(bbox)
        val w = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.width()
        val h = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.height()
        return Result(
            type = ShapeType.STAR,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = max(w, 1f),
            height = max(h, 1f),
            rotationDeg = 0f,
            aspectLocked = aspectLocked,
            pointCount = n,
        )
    }

    private fun lineResult(
        type: ShapeType, bbox: RectF, confidence: Float,
        fromPt: PointF? = null, toPt: PointF? = null,
    ): Result? {
        if (confidence < SHAPE_MIN_CONFIDENCE) return null
        return if (fromPt != null && toPt != null) {
            val angle = atan2((toPt.y - fromPt.y).toDouble(), (toPt.x - fromPt.x).toDouble())
            val rawDeg = Math.toDegrees(angle).toFloat()
            // Both lines and arrows snap to the nearest cardinal direction (horizontal/vertical).
            val rotDeg = snapToCardinal(rawDeg)
            // Width = bbox extent in the snapped direction so the rendered shape spans the drawn area.
            val width = if (rotDeg == 90f || rotDeg == 270f) max(bbox.height(), 1f) else max(bbox.width(), 1f)
            Result(
                type = type,
                centerX = bbox.centerX(),
                centerY = bbox.centerY(),
                width  = width,
                height = max(min(bbox.width(), bbox.height()), 1f),
                rotationDeg = rotDeg,
                aspectLocked = false,
            )
        } else {
            Result(
                type = type,
                centerX = bbox.centerX(),
                centerY = bbox.centerY(),
                width  = max(bbox.width(), 1f),
                height = max(bbox.height(), 1f),
                rotationDeg = 0f,
                aspectLocked = false,
            )
        }
    }

    private fun snapToCardinal(angleDeg: Float): Float {
        val n = ((angleDeg % 360f) + 360f) % 360f
        return when {
            n < 45f || n >= 315f -> 0f    // right
            n < 135f -> 90f               // down
            n < 225f -> 180f              // left
            else -> 270f                  // up
        }
    }

    private fun hasArrowHead(tail: List<PointF>, shaftStart: PointF, shaftEnd: PointF): Boolean {
        val shaftDx = shaftEnd.x - shaftStart.x
        val shaftDy = shaftEnd.y - shaftStart.y
        val shaftLen = sqrt((shaftDx * shaftDx + shaftDy * shaftDy).toDouble()).toFloat()
        if (shaftLen == 0f || tail.size < 3) return false
        val ux = shaftDx / shaftLen
        val uy = shaftDy / shaftLen
        // A real arrowhead arm travels backward a visible distance — at least 15% of the shaft
        // length. Checking individual dense-resampled segments (which are tiny and can wobble on
        // pen lift) was too sensitive. Project each tail point onto the shaft axis relative to
        // shaftEnd: a genuine arm will put at least one point well behind the shaft tip.
        val minProjection = tail.minOf { p ->
            (p.x - shaftEnd.x) * ux + (p.y - shaftEnd.y) * uy
        }
        return minProjection < -shaftLen * 0.15f
    }

    // RDP-based arrow detection for non-standard draw orders:
    //   • Arrowhead-first: user draws the V tip, then the shaft (shaft is last 80%, not first 80%).
    //   • Mid-stroke arrowhead: shaft → V → partial return (the V is at 40-75%, not in the last 20%).
    // Strategy: the longest RDP segment is the shaft. Non-shaft segments form the arrowhead.
    // Criteria: (1) shaft dominates (≥ 1.4× second-longest), (2) at least one RDP corner has
    // angle < 60° (the arrowhead tip), (3) ≥ 1 non-shaft segment goes backward against the shaft.
    private fun tryRdpArrow(rdpPts: List<PointF>, bbox: RectF): Result? {
        val n = rdpPts.size
        if (n < 3) return null

        // Find longest segment (shaft)
        var maxLen = 0f; var shaftIdx = 0
        for (i in 0 until n - 1) {
            val len = dist(rdpPts[i], rdpPts[i + 1])
            if (len > maxLen) { maxLen = len; shaftIdx = i }
        }
        // Shaft must be clearly dominant — otherwise it's a curve or zigzag, not an arrow.
        // 1.5× matches tryRecognizeOpenStar; 1.2× was too loose and let gently bent lines through.
        var secondLen = 0f
        for (i in 0 until n - 1) {
            val len = dist(rdpPts[i], rdpPts[i + 1])
            if (i != shaftIdx && len > secondLen) secondLen = len
        }
        if (secondLen > 0f && maxLen < secondLen * 1.5f) return null

        // Must have a sharp corner (the arrowhead tip). Smooth arches/curves rarely produce
        // interior angles < 75° in RDP; a V-shaped arrowhead almost always does.
        var minAngle = Float.MAX_VALUE
        for (i in 1 until n - 1) {
            val a = interiorAngleDeg(rdpPts[i - 1], rdpPts[i], rdpPts[i + 1])
            if (a < minAngle) minAngle = a
        }
        if (minAngle > 75f) return null

        // Count non-shaft segments going backward (dot < -0.2) relative to shaft direction,
        // tracking whether they appear before or after the shaft index.
        // Segments before shaftIdx connect to rdpPts[shaftIdx] (the shaft's near endpoint);
        // segments after connect to rdpPts[shaftIdx+1] (the far endpoint).
        // Whichever shaft endpoint is adjacent to more backward segments = the arrowhead end.
        val shaftDx = rdpPts[shaftIdx + 1].x - rdpPts[shaftIdx].x
        val shaftDy = rdpPts[shaftIdx + 1].y - rdpPts[shaftIdx].y
        val shaftLen = dist(rdpPts[shaftIdx], rdpPts[shaftIdx + 1])
        var reversalsBefore = 0
        var reversalsAfter = 0
        for (i in 0 until n - 1) {
            if (i == shaftIdx) continue
            val dx = rdpPts[i + 1].x - rdpPts[i].x
            val dy = rdpPts[i + 1].y - rdpPts[i].y
            val segLen = dist(rdpPts[i], rdpPts[i + 1])
            if (segLen == 0f) continue
            val dot = (dx * shaftDx + dy * shaftDy) / (segLen * shaftLen)
            if (dot < -0.2f) { if (i < shaftIdx) reversalsBefore++ else reversalsAfter++ }
        }
        if (reversalsBefore + reversalsAfter < 1) return null
        val fromPt: PointF
        val toPt: PointF
        if (reversalsBefore > reversalsAfter) {
            // Backward segs precede shaft → arrowhead at rdpPts[shaftIdx] → arrow points there
            fromPt = rdpPts[shaftIdx + 1]; toPt = rdpPts[shaftIdx]
        } else {
            // Backward segs follow shaft → arrowhead at rdpPts[shaftIdx+1]
            fromPt = rdpPts[shaftIdx]; toPt = rdpPts[shaftIdx + 1]
        }
        return lineResult(ShapeType.ARROW, bbox, confidence = 0.70f, fromPt = fromPt, toPt = toPt)
    }

    private fun isArch(pts: List<PointF>): Boolean {
        val centroid = centroid(pts)
        var totalSweep = 0.0
        for (i in 1 until pts.size) {
            val a1 = atan2((pts[i-1].y - centroid.y).toDouble(), (pts[i-1].x - centroid.x).toDouble())
            val a2 = atan2((pts[i].y   - centroid.y).toDouble(), (pts[i].x   - centroid.x).toDouble())
            var diff = a2 - a1
            while (diff >  PI) diff -= 2 * PI
            while (diff < -PI) diff += 2 * PI
            totalSweep += abs(diff)
        }
        return totalSweep in (PI * 0.75)..(PI * 1.4)
    }

    // ── Closed shape recognition ──────────────────────────────────────────────

    private fun recognizeClosed(pts: List<PointF>, diag: Float, bbox: RectF): Result? {
        // Radial profile: distance of each resampled point from centroid
        val centroid = centroid(pts)
        val radii = pts.map { dist(it, centroid) }
        val meanR = radii.average().toFloat()
        val cv = if (meanR > 0f) {
            sqrt(radii.map { (it - meanR) * (it - meanR) }.average()).toFloat() / meanR
        } else {
            Float.MAX_VALUE
        }

        // Star: count strong alternating radial peaks (before simplification)
        val starResult = tryRecognizeStar(pts, centroid, meanR, bbox)
        if (starResult != null) return starResult

        // RDP simplification for polygon corner counting — run BEFORE ellipse test
        // so rectangles with low radial CV are never misclassified as ellipses.
        val eps = SHAPE_RDP_EPS_RATIO * diag
        val simplified = rdp(pts, eps)
        val corners = if (simplified.size > 1 && dist(simplified.first(), simplified.last()) < eps * 2) {
            simplified.dropLast(1)
        } else {
            simplified
        }

        // Remove RDP points that are nearly collinear (spurious side-of-edge points from a wobbly draw).
        // A rough square can produce 5 corners when one side has a slight bump; pruning the ~180° point
        // brings it back to 4 corners so it classifies as RECTANGLE rather than PENTAGON.
        val prunedCorners = pruneLinearCorners(corners)

        // Skip-pattern star drawn closed (user returns near start): RDP corners = outer tips,
        // all with sharp interior angles. Must run before classifyByCorners or a 5-tip star
        // gets misclassified as PENTAGON.
        val closedStarResult = tryRecognizeClosedStar(prunedCorners, bbox)
        if (closedStarResult != null) return closedStarResult

        val polyResult = classifyByCorners(prunedCorners, bbox, diag)

        // Gate every polygon candidate on side flatness measured against the full resampled pts.
        // True polygon sides are straight (deviation/length < SHAPE_LINE_STRAIGHTNESS = 0.10).
        // Circle arcs score 0.13–0.21 at the corner counts RDP produces (4–7), so they fail
        // the gate and fall through to the ellipse block below.
        // Previously the 5–9 path used a fine-RDP corner-count ratio, but the circle ratio
        // (~1.4×) was too close to the threshold; sidesAreStraight is a direct measurement.
        if (polyResult != null && sidesAreStraight(pts, prunedCorners)) return polyResult

        // Ellipse / circle: three tiers by CV and corner context, plus a fit-quality fallback.
        //   Tier 1 (cv ≤ 0.18): clearly smooth — any corner count
        //   Tier 2 (cv ≤ 0.29, corners ≥ 5): moderately elongated oval with extra RDP points
        //   Tier 3 (cv ≤ 0.45, prunedCorners == 4 & polyResult null): very elongated oval whose
        //     poles triggered the diamond acute-angle guard; classifyQuad returned null to signal.
        //   Tier 4 (fit quality): elongated ovals drawn at a diagonal have inherently high radial
        //     CV (a 2.5:1 ellipse gives cv ≈ 0.30). When CV tiers fail but corners ≥ 5, compute
        //     the ellipse fit error: if points lie close to the best-fit ellipse, it's still an oval.
        val ellipseCV = if (prunedCorners.size <= 4) SHAPE_ELLIPSE_CV * 2.5f else SHAPE_ELLIPSE_CV * 1.6f
        val cvPasses = cv <= SHAPE_ELLIPSE_CV || (corners.size >= 5 && cv <= ellipseCV)

        if (cvPasses || (corners.size >= 5 && cv <= SHAPE_ELLIPSE_CV * 3f)) {
            val aspectLocked = isNearSquare(bbox)
            // Find major axis via the farthest pair of resampled points, then project all
            // points onto that axis and the perpendicular to get the true semi-axes.
            var maxPairDist = 0f; var pA = pts[0]; var pB = pts[0]
            for (i in pts.indices) {
                for (j in i + 1 until pts.size) {
                    val d = dist(pts[i], pts[j])
                    if (d > maxPairDist) { maxPairDist = d; pA = pts[i]; pB = pts[j] }
                }
            }
            val majorAngle = atan2((pB.y - pA.y).toDouble(), (pB.x - pA.x).toDouble())
            val cosA = cos(majorAngle).toFloat(); val sinA = sin(majorAngle).toFloat()
            var minMaj = Float.MAX_VALUE; var maxMaj = -Float.MAX_VALUE
            var minMin = Float.MAX_VALUE; var maxMin = -Float.MAX_VALUE
            for (pt in pts) {
                val dx = pt.x - centroid.x; val dy = pt.y - centroid.y
                val projMaj = dx * cosA + dy * sinA
                val projMin = -dx * sinA + dy * cosA
                if (projMaj < minMaj) minMaj = projMaj; if (projMaj > maxMaj) maxMaj = projMaj
                if (projMin < minMin) minMin = projMin; if (projMin > maxMin) maxMin = projMin
            }
            val semiMaj = max((maxMaj - minMaj) / 2f, 1f)
            val semiMin = max((maxMin - minMin) / 2f, 1f)

            // Tier 4: check fit quality when CV tiers didn't pass.
            val fitsEllipse = if (cvPasses) true else {
                var fitErrorSum = 0f
                for (pt in pts) {
                    val dx = pt.x - centroid.x; val dy = pt.y - centroid.y
                    val pm = dx * cosA + dy * sinA
                    val pn = -dx * sinA + dy * cosA
                    val rNorm = sqrt((pm / semiMaj) * (pm / semiMaj) + (pn / semiMin) * (pn / semiMin))
                    fitErrorSum += abs(rNorm - 1f)
                }
                (fitErrorSum / pts.size) < SHAPE_ELLIPSE_FIT_THRESHOLD
            }

            if (fitsEllipse) {
                val ew = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.width()
                val eh = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.height()
                return Result(
                    type = ShapeType.ELLIPSE,
                    centerX = bbox.centerX(),
                    centerY = bbox.centerY(),
                    width = max(ew, 1f),
                    height = max(eh, 1f),
                    rotationDeg = 0f,
                    aspectLocked = aspectLocked,
                )
            }
        }

        if (polyResult != null) return polyResult
        return null
    }

    private fun tryRecognizeStar(pts: List<PointF>, centroid: PointF, meanR: Float, bbox: RectF): Result? {
        if (pts.size < 10) return null
        val radii = pts.map { dist(it, centroid) }
        val threshold = meanR * 0.15f

        var peaks = 0
        var troughs = 0
        for (i in 1 until radii.size - 1) {
            if (radii[i] > radii[i-1] + threshold && radii[i] > radii[i+1] + threshold) peaks++
            if (radii[i] < radii[i-1] - threshold && radii[i] < radii[i+1] - threshold) troughs++
        }

        if (peaks < 4 || abs(peaks - troughs) > peaks / 2) return null

        val pointCount = peaks.coerceIn(5, 12)
        val aspectLocked = isNearSquare(bbox)
        return Result(
            type = ShapeType.STAR,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = max(bbox.width(), 1f),
            height = if (aspectLocked) max(bbox.width(), 1f) else max(bbox.height(), 1f),
            rotationDeg = 0f,
            aspectLocked = aspectLocked,
            pointCount = pointCount,
        )
    }

    private fun classifyByCorners(corners: List<PointF>, bbox: RectF, diag: Float): Result? {
        return when (corners.size) {
            3 -> classifyTriangle(corners, bbox)
            4 -> classifyQuad(corners, bbox, diag)
            5, 6, 7, 8, 9 -> classifyConvexPolygon(corners, bbox)
            else -> null
        }
    }

    // Angle-based pentagon/hexagon classifier that tolerates ±1 drawing-noise corner.
    // Exact corner count is unreliable: a neatly drawn pentagon can produce 4–6 RDP corners.
    // Instead, require all corners to be obtuse and use the average interior angle to pick the
    // polygon type. Regular pentagon = 108°, hexagon = 120° — split at 116° between them.
    private fun classifyConvexPolygon(corners: List<PointF>, bbox: RectF): Result? {
        val n = corners.size
        val angles = List(n) { i ->
            interiorAngleDeg(corners[(i - 1 + n) % n], corners[i], corners[(i + 1) % n])
        }
        if (angles.any { it < 70f }) return null  // sharp corner → star or misclassified shape
        val avg = angles.average().toFloat()
        return when {
            avg < 80f || avg > 145f -> null  // degenerate
            avg < 116f -> regularized(ShapeType.PENTAGON, bbox, isNearSquare(bbox))
            else       -> regularized(ShapeType.HEXAGON,  bbox, false)
        }
    }

    // Detect which cardinal direction the triangle's apex points, and set rotationDeg so the
    // rendered shape matches the drawn orientation (right-pointing drawn → right-pointing shape).
    private fun classifyTriangle(corners: List<PointF>, bbox: RectF): Result {
        val n = corners.size
        // Apex = sharpest corner (smallest interior angle)
        var apexIdx = 0; var minAngle = Float.MAX_VALUE
        for (i in 0 until n) {
            val a = interiorAngleDeg(corners[(i - 1 + n) % n], corners[i], corners[(i + 1) % n])
            if (a < minAngle) { minAngle = a; apexIdx = i }
        }
        val apex = corners[apexIdx]
        val dx = apex.x - bbox.centerX()
        val dy = apex.y - bbox.centerY()
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // snapToCardinal: 0f=right 90f=down 180f=left 270f=up
        val cardinal = snapToCardinal(angleDeg)
        // Triangle geometry (unrotated): apex at top. Rotate so apex points the detected direction.
        val rotationDeg = when (cardinal) {
            0f   -> 90f   // apex right  → 90° CW
            90f  -> 180f  // apex down   → 180°
            180f -> 270f  // apex left   → 270° CW
            else -> 0f    // 270f=up     → no rotation
        }
        val aspectLocked = isNearSquare(bbox)
        val rawW = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.width()
        val rawH = if (aspectLocked) max(bbox.width(), bbox.height()) else bbox.height()
        // Swap w/h for 90°/270° so the visual bbox after rotation matches the drawn stroke bbox.
        val (w, h) = if (rotationDeg == 90f || rotationDeg == 270f) Pair(rawH, rawW) else Pair(rawW, rawH)
        return Result(
            type = ShapeType.TRIANGLE,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = max(w, 1f),
            height = max(h, 1f),
            rotationDeg = rotationDeg,
            aspectLocked = aspectLocked,
        )
    }

    private fun classifyQuad(corners: List<PointF>, bbox: RectF, diag: Float): Result? {
        // Order corners by angle around centroid
        val centroid = PointF(bbox.centerX(), bbox.centerY())
        val ordered  = corners.sortedBy { atan2((it.y - centroid.y).toDouble(), (it.x - centroid.x).toDouble()) }

        // Diamond: corners near the 4 cardinal midpoints of the bbox
        val snapDist  = diag * 0.18f
        val midpoints = listOf(
            PointF(bbox.centerX(), bbox.top),
            PointF(bbox.right,     bbox.centerY()),
            PointF(bbox.centerX(), bbox.bottom),
            PointF(bbox.left,      bbox.centerY()),
        )
        // A true diamond has one corner near each of the 4 cardinal bbox midpoints (one per midpoint).
        // Counting corners-near-any-midpoint is wrong: a wide rectangle's corners sit at the bbox
        // corners, but each corner is only height/2 from the nearest side midpoint — within snap
        // distance for flat shapes. Fix: count distinct midpoints that have a corner near them.
        val midpointsCovered = midpoints.count { m -> ordered.any { c -> dist(c, m) < snapDist } }
        if (midpointsCovered >= 3) {
            // Acute-angle guard: only reject highly degenerate shapes (< 25° at any corner).
            // A 2:1 wide diamond has ~53° at its left/right corners — must be allowed.
            // Extremely elongated ovals (4:1+) that slip past the midpoint check are the only
            // real risk, and they'd have angles < 25°.
            val minAngle = ordered.indices.minOf { i ->
                interiorAngleDeg(ordered[(i - 1 + 4) % 4], ordered[i], ordered[(i + 1) % 4])
            }
            if (minAngle < 25f) return null
            return regularized(ShapeType.DIAMOND, bbox, false)
        }

        // Trapezoid: two roughly horizontal edges with distinctly different widths.
        // Threshold 0.65: a user-drawn trapezoid typically has the narrow edge at 50–80% of
        // the wider edge. The original 0.50 (2:1) threshold was too strict and missed most
        // real trapezoids. Rectangles drawn cleanly have width ratios above 0.80; even a
        // moderately wobbly rectangle rarely dips below 0.70.
        val sortedByY = ordered.sortedBy { it.y }
        val topTwo    = sortedByY.take(2).sortedBy { it.x }
        val botTwo    = sortedByY.takeLast(2).sortedBy { it.x }
        val topWidth  = abs(topTwo[1].x - topTwo[0].x)
        val botWidth  = abs(botTwo[1].x - botTwo[0].x)
        val maxW      = max(topWidth, botWidth)
        val widthRatio = if (maxW > 0f) min(topWidth, botWidth) / maxW else 1f
        if (widthRatio < 0.65f) {
            // A very narrow end (< 15% of the wider end) means the drawn shape is really a
            // triangle whose apex wobbled into two nearby RDP points. Collapse those two points
            // into a synthetic apex and classify as triangle with correct direction.
            if (widthRatio < 0.15f) {
                val apexAtBottom = botWidth < topWidth
                val wideTwo = if (apexAtBottom) topTwo else botTwo
                val narrowTwo = if (apexAtBottom) botTwo else topTwo
                val apexPt = PointF(
                    (narrowTwo[0].x + narrowTwo[1].x) / 2f,
                    (narrowTwo[0].y + narrowTwo[1].y) / 2f,
                )
                return classifyTriangle(listOf(wideTwo[0], wideTwo[1], apexPt), bbox)
            }
            return regularized(ShapeType.TRAPEZOID, bbox, false)
        }

        // Default: rectangle — axis-aligned using actual drawn side lengths (not bbox) so a
        // tilted rectangle gets the correct proportions after snapping to horizontal/vertical.
        val naturalW = (dist(ordered[0], ordered[1]) + dist(ordered[2], ordered[3])) / 2f
        val naturalH = (dist(ordered[1], ordered[2]) + dist(ordered[3], ordered[0])) / 2f
        val aspectLocked = isNearSquare(bbox)
        val sw = if (aspectLocked) max(naturalW, naturalH) else naturalW
        val sh = if (aspectLocked) max(naturalW, naturalH) else naturalH
        return Result(
            type = ShapeType.RECTANGLE,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = max(sw, 1f),
            height = max(sh, 1f),
            rotationDeg = 0f,
            aspectLocked = aspectLocked,
        )
    }

    // ── Corner pruning ────────────────────────────────────────────────────────

    /**
     * Returns true when every side of the [corners] polygon is actually flat against the full
     * resampled [pts]. For each side A→B, any resampled point whose projection falls strictly
     * inside the segment is checked; if its perpendicular deviation exceeds
     * SHAPE_LINE_STRAIGHTNESS * side_length the side is curved (→ circle/oval, not a polygon).
     */
    private fun sidesAreStraight(pts: List<PointF>, corners: List<PointF>): Boolean {
        val n = corners.size
        var ratioSum = 0f
        var checked = 0
        for (i in 0 until n) {
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val dx = b.x - a.x; val dy = b.y - a.y
            val segLen = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (segLen == 0f) continue
            val ux = dx / segLen; val uy = dy / segLen
            var maxDev = 0f
            for (pt in pts) {
                val t = (pt.x - a.x) * ux + (pt.y - a.y) * uy
                if (t <= 0f || t >= segLen) continue
                val dev = abs((pt.x - a.x) * uy - (pt.y - a.y) * ux)
                if (dev > maxDev) maxDev = dev
            }
            ratioSum += maxDev / segLen
            checked++
        }
        // Average max-deviation ratio across sides. A circle's sides all score ~0.134+ (6-corner)
        // to 0.207 (4-corner). One wobbly polygon side raises the average only slightly — a hexagon
        // with one bad side (0.20) and five clean sides (0.04) averages to ~0.07, well below 0.12.
        return checked == 0 || (ratioSum / checked) <= SHAPE_SIDE_STRAIGHTNESS_AVG
    }

    /** Remove corners whose interior angle is > [maxAngleDeg] — they lie on an edge, not at a real corner. */
    private fun pruneLinearCorners(corners: List<PointF>, maxAngleDeg: Float = 150f): List<PointF> {
        if (corners.size <= 3) return corners
        val result = mutableListOf<PointF>()
        val n = corners.size
        for (i in 0 until n) {
            val prev = corners[(i - 1 + n) % n]
            val curr = corners[i]
            val next = corners[(i + 1) % n]
            if (interiorAngleDeg(prev, curr, next) <= maxAngleDeg) result.add(curr)
        }
        return if (result.size >= 3) result else corners
    }

    private fun interiorAngleDeg(prev: PointF, curr: PointF, next: PointF): Float {
        val v1x = prev.x - curr.x; val v1y = prev.y - curr.y
        val v2x = next.x - curr.x; val v2y = next.y - curr.y
        val len1 = sqrt((v1x * v1x + v1y * v1y).toDouble()).toFloat()
        val len2 = sqrt((v2x * v2x + v2y * v2y).toDouble()).toFloat()
        if (len1 == 0f || len2 == 0f) return 180f
        val cosA = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosA.toDouble())).toFloat()
    }

    // ── Regularization helpers ────────────────────────────────────────────────

    private fun regularized(type: ShapeType, bbox: RectF, aspectLocked: Boolean): Result {
        val w = max(bbox.width(), 1f)
        val h = max(bbox.height(), 1f)
        val snappedW = if (aspectLocked) max(w, h) else w
        val snappedH = if (aspectLocked) max(w, h) else h
        return Result(
            type = type,
            centerX = bbox.centerX(),
            centerY = bbox.centerY(),
            width = snappedW,
            height = snappedH,
            rotationDeg = 0f,
            aspectLocked = aspectLocked,
        )
    }

    private fun closedResult(type: ShapeType, bbox: RectF, aspectLocked: Boolean, confidence: Float): Result? {
        if (confidence < SHAPE_MIN_CONFIDENCE) return null
        return regularized(type, bbox, aspectLocked)
    }

    private fun isNearSquare(bbox: RectF): Boolean {
        val w = bbox.width()
        val h = bbox.height()
        val maxWH = max(w, h)
        return if (maxWH > 0f) abs(w - h) / maxWH <= SHAPE_SQUARE_SNAP else false
    }

    // ── Geometry utilities ────────────────────────────────────────────────────

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun boundingBox(pts: List<PointF>): RectF {
        var l = pts[0].x; var t = pts[0].y; var r = l; var b = t
        for (p in pts) {
            if (p.x < l) l = p.x; if (p.x > r) r = p.x
            if (p.y < t) t = p.y; if (p.y > b) b = p.y
        }
        return RectF(l, t, r, b)
    }

    private fun centroid(pts: List<PointF>): PointF {
        var sx = 0f; var sy = 0f
        for (p in pts) { sx += p.x; sy += p.y }
        return PointF(sx / pts.size, sy / pts.size)
    }

    private fun maxPerpendicularDeviation(pts: List<PointF>, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len == 0f) return pts.maxOf { dist(it, a) }
        var maxDev = 0f
        for (p in pts) {
            val dev = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
            if (dev > maxDev) maxDev = dev
        }
        return maxDev
    }

    // ── Resampling ────────────────────────────────────────────────────────────

    private fun resample(pts: List<PointF>, n: Int): List<PointF> {
        if (pts.size <= 1) return pts.toList()
        val segments = mutableListOf(0f)
        for (i in 1 until pts.size) segments.add(segments.last() + dist(pts[i - 1], pts[i]))
        val totalLen = segments.last()
        if (totalLen == 0f) return List(n) { pts.first() }
        val interval = totalLen / (n - 1)
        val result = mutableListOf<PointF>()
        result.add(pts.first())
        var segIdx = 0
        for (i in 1 until n - 1) {
            val targetDist = i * interval
            while (segIdx < segments.size - 2 && segments[segIdx + 1] < targetDist) segIdx++
            if (segIdx >= pts.size - 1) { result.add(pts.last()); continue }
            val span = segments[segIdx + 1] - segments[segIdx]
            val t = if (span > 0f) (targetDist - segments[segIdx]) / span else 0f
            result.add(PointF(
                pts[segIdx].x + t * (pts[segIdx + 1].x - pts[segIdx].x),
                pts[segIdx].y + t * (pts[segIdx + 1].y - pts[segIdx].y),
            ))
        }
        result.add(pts.last())
        return result
    }

    // ── Ramer–Douglas–Peucker ────────────────────────────────────────────────

    private fun rdp(pts: List<PointF>, eps: Float): List<PointF> {
        if (pts.size <= 2) return pts.toList()
        val first = pts.first(); val last = pts.last()
        var maxDev = 0f; var maxIdx = 0
        for (i in 1 until pts.size - 1) {
            val dev = perpendicularDist(pts[i], first, last)
            if (dev > maxDev) { maxDev = dev; maxIdx = i }
        }
        return if (maxDev <= eps) {
            listOf(first, last)
        } else {
            rdp(pts.subList(0, maxIdx + 1), eps) +
            rdp(pts.subList(maxIdx, pts.size), eps).drop(1)
        }
    }

    private fun perpendicularDist(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        return if (len == 0f) dist(p, a)
        else abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }
}
