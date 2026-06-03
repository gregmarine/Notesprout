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
