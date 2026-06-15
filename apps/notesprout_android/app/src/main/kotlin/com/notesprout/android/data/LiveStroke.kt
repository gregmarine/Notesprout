package com.notesprout.android.data

import android.graphics.PointF
import android.graphics.RectF
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An in-memory stroke with a stable UUID.
 *
 * The [id] matches the `NotebookObject.id` of the corresponding row in the
 * `notebook` table, enabling incremental saves (INSERT OR IGNORE) and targeted
 * soft-deletes when a stroke is erased without a full page re-write.
 *
 * UUID is assigned at stroke creation time inside the drawing view.
 *
 * [boundingBox] is pre-computed at construction time for O(1) AABB pre-filtering
 * during eraser hit tests — avoids the full per-point geometry check on strokes
 * that are nowhere near the eraser position.
 *
 * Annotated [@Serializable] so [StrokesMoved] undo/redo actions can carry full point
 * data.  [boundingBox] is a body property (not a constructor param) so kotlinx.serialization
 * ignores it; it is recomputed correctly on deserialization.
 */
@Serializable
data class LiveStroke(
    /** UUID matching the notebook table row for this stroke. */
    val id: String,

    /** Ordered (x, y) points in drawing-view coordinates. */
    @Serializable(with = PointFListSerializer::class)
    val points: List<PointF>,

    /**
     * Stroke colour as a `#RRGGBB`/`#AARRGGBB` string. Preserved verbatim across
     * moves/copies/conversions so a re-save never fabricates a colour. Defaults to
     * [DEFAULT_COLOR] for freshly drawn strokes (the only path with no prior data).
     */
    val color: String = DEFAULT_COLOR,

    /** Stroke width in px. Preserved across re-serialization. */
    val strokeWidth: Float = DEFAULT_STROKE_WIDTH,

    /**
     * The original captured samples (x/y/pressure/tilt/timestamp) this stroke was
     * loaded from, or null for strokes created this session with no persisted source.
     * Carries pressure/tilt/timestamp through moves so they are not destroyed on
     * re-save. [toStrokeData] reads x/y from [points] (which may have been translated)
     * and pressure/tilt/timestamp from here when the two are index-aligned.
     */
    val srcPoints: List<StrokePoint>? = null,
) {
    /**
     * Axis-aligned bounding box of all stroke points, computed once at construction.
     * Used as a fast rejection test in eraseAtPath() before the expensive per-point check.
     * [@Transient] excludes it from serialization — it is recomputed from [points] on decode.
     */
    @Transient
    val boundingBox: RectF = if (points.isEmpty()) RectF() else run {
        var minX = points[0].x; var minY = points[0].y
        var maxX = minX;        var maxY = minY
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x < minX) minX = p.x else if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y else if (p.y > maxY) maxY = p.y
        }
        RectF(minX, minY, maxX, maxY)
    }

    /**
     * Re-serialize this stroke to its persisted form, preserving colour, width, and
     * per-point pressure/tilt/timestamp from [srcPoints] when available. Current x/y
     * always come from [points] (so translated strokes save their new position).
     *
     * [fallbackTimestamp] is stamped only on points with no preserved source — i.e.
     * freshly drawn strokes that have never been persisted.
     */
    fun toStrokeData(fallbackTimestamp: Long): StrokeData {
        val src = srcPoints
        val outPoints = if (src != null && src.size == points.size) {
            points.mapIndexed { i, p ->
                StrokePoint(x = p.x, y = p.y, pressure = src[i].pressure, tilt = src[i].tilt, timestamp = src[i].timestamp)
            }
        } else {
            points.map { p -> StrokePoint(x = p.x, y = p.y, pressure = null, tilt = null, timestamp = fallbackTimestamp) }
        }
        return StrokeData(color = color, strokeWidth = strokeWidth, points = outPoints)
    }

    companion object {
        const val DEFAULT_COLOR = "#000000"
        const val DEFAULT_STROKE_WIDTH = 3.0f

        /**
         * Build an in-memory stroke from a persisted [StrokeData], preserving colour,
         * width, and the original sample list so subsequent re-saves do not fabricate data.
         */
        fun fromStrokeData(id: String, sd: StrokeData): LiveStroke = LiveStroke(
            id = id,
            points = sd.toPointFs(),
            color = sd.color,
            strokeWidth = sd.strokeWidth,
            srcPoints = sd.points,
        )
    }
}

// ── Serialization support for android.graphics.PointF ────────────────────────

@Serializable
private data class PointFSurrogate(val x: Float, val y: Float)

private object PointFSerializer : KSerializer<PointF> {
    private val delegate = PointFSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: PointF) =
        delegate.serialize(encoder, PointFSurrogate(value.x, value.y))
    override fun deserialize(decoder: Decoder): PointF {
        val s = delegate.deserialize(decoder)
        return PointF(s.x, s.y)
    }
}

internal object PointFListSerializer : KSerializer<List<PointF>> {
    private val delegate = ListSerializer(PointFSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<PointF>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<PointF> = delegate.deserialize(decoder)
}
