package com.notesprout.android.data

import android.graphics.PointF
import android.graphics.RectF
import com.notesprout.android.core.StrokeCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

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
     * The original captured samples (x/y/pressure/tilt) this stroke was loaded from,
     * or null for strokes created this session with no persisted source. Carries
     * pressure/tilt through moves so they are not destroyed on re-save. [toStrokeData]
     * reads x/y from [points] (which may have been translated) and pressure/tilt from
     * here when the two are index-aligned. (A legacy per-point `ts` may still ride along
     * on rows loaded from old data, but it is never re-written — see [StrokePoint].)
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
     * per-point pressure/tilt from [srcPoints] when available. Current x/y always come
     * from [points] (so translated strokes save their new position).
     *
     * Per-point timestamps are intentionally not written (see [StrokePoint.timestamp]):
     * they were dead weight, and a stroke's creation time already lives on its row's
     * `createdAt`. Old rows that still carry `"ts"` shed it here on their next save.
     */
    fun toStrokeData(): StrokeData {
        val src = srcPoints
        val outPoints = if (src != null && src.size == points.size) {
            points.mapIndexed { i, p ->
                StrokePoint(x = p.x, y = p.y, pressure = src[i].pressure, tilt = src[i].tilt)
            }
        } else {
            points.map { p -> StrokePoint(x = p.x, y = p.y) }
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

        private val leanCodec = Json { ignoreUnknownKeys = true }

        /**
         * Lean load path for stroke rows with NO per-point pressure/tilt (all current data — hardware
         * capture is unimplemented). Parses `points` straight into [PointF] via [PointFListSerializer],
         * skipping the intermediate [StrokePoint] allocation — one object per point instead of two,
         * roughly halving the per-point garbage that dominates dense-page load. [srcPoints] is null
         * (nothing to preserve). Callers MUST gate on the absence of pressure/tilt and fall back to
         * [fromStrokeData] otherwise, so those samples are never silently dropped.
         */
        fun fromPointsJson(id: String, json: String): LiveStroke {
            val lean = leanCodec.decodeFromString(LeanStrokeSurrogate.serializer(), json)
            return LiveStroke(
                id = id,
                points = lean.points,
                color = lean.color,
                strokeWidth = lean.strokeWidth,
                srcPoints = null,
            )
        }

        // ── Binary format (data-model-optimization Phase 1) ────────────────────

        /**
         * Pack points to the binary stroke blob ([StrokeCodec] format B — float32 + zlib, lossless).
         * Colour and width live in the row's own columns, so the blob is geometry only.
         */
        fun packPoints(points: List<PointF>): ByteArray {
            val xy = FloatArray(points.size * 2)
            for (i in points.indices) { xy[i * 2] = points[i].x; xy[i * 2 + 1] = points[i].y }
            return StrokeCodec.encode(xy)
        }

        /** Inverse of [packPoints]. */
        fun unpackPoints(blob: ByteArray): List<PointF> {
            val xy = StrokeCodec.decode(blob)
            val out = ArrayList<PointF>(xy.size / 2)
            var i = 0
            while (i < xy.size) { out.add(PointF(xy[i], xy[i + 1])); i += 2 }
            return out
        }

        /**
         * Format-agnostic decode of a stroke row: prefer the binary [NotebookObject.blob] (with colour
         * and width from the row columns); otherwise fall back to the legacy JSON in
         * [NotebookObject.data] — lean, points-only path when there is no per-point pressure/tilt (all
         * current data), full [StrokeData] otherwise. [lean] forces the points-only path (thumbnails).
         * Returns null on malformed data.
         */
        fun fromRow(obj: NotebookObject, lean: Boolean = false): LiveStroke? = try {
            val blob = obj.blob
            if (blob != null && blob.isNotEmpty()) {
                LiveStroke(
                    id = obj.id,
                    points = unpackPoints(blob),
                    color = obj.color ?: DEFAULT_COLOR,
                    strokeWidth = obj.strokeWidth ?: DEFAULT_STROKE_WIDTH,
                    srcPoints = null,
                )
            } else {
                val data = obj.data
                if (lean || (data.indexOf("pressure") < 0 && data.indexOf("tilt") < 0)) {
                    fromPointsJson(obj.id, data)
                } else {
                    fromStrokeData(obj.id, StrokeData.fromJson(data))
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Build a columnar stroke row (data-model-optimization Phase 1): points → binary [NotebookObject.blob],
 * colour/width → columns. The bounding box is derived from points on load, so `boundingBox`/`data`
 * stay empty (the legacy columns are NOT NULL, hence `""`).
 */
fun LiveStroke.toStrokeRow(
    parentId: String,
    order: Int,
    createdAt: Long,
    updatedAt: Long,
    deletedAt: Long? = null,
): NotebookObject = NotebookObject(
    id = id,
    parentId = parentId,
    boundingBox = "",
    sortOrder = order,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    type = TYPE_STROKE,
    data = "",
    color = color,
    strokeWidth = strokeWidth,
    blob = LiveStroke.packPoints(points),
)

/** The binary blob for this stroke's current points — used to persist translated coordinates. */
fun LiveStroke.strokeBlob(): ByteArray = LiveStroke.packPoints(points)

/**
 * Deep-copy this stroke, cloning its points so the copy can be moved without aliasing the original,
 * and **preserving every other field** — colour, width, and the original pressure/tilt samples.
 *
 * Use this (or [translated]) instead of rebuilding a stroke with the `LiveStroke(id, points)`
 * constructor. That two-argument form silently defaults `color` back to black, `strokeWidth` back to
 * 3.0, and `srcPoints` to null, so every copy/move/paste/undo path that used it was quietly
 * flattening the user's ink. It was invisible while all ink was black; it is data loss now.
 */
fun LiveStroke.deepCopy(newId: String = id): LiveStroke =
    copy(id = newId, points = points.map { PointF(it.x, it.y) })

/**
 * [deepCopy] shifted by ([dx], [dy]) — for moves, pastes, and link-subtree translation.
 *
 * `srcPoints` rides along untouched and stays index-aligned with the shifted points, which is exactly
 * what [LiveStroke.toStrokeData] expects: it reads x/y from `points` and pressure/tilt from
 * `srcPoints` by index, so a translated stroke keeps its stylus data.
 */
fun LiveStroke.translated(dx: Float, dy: Float, newId: String = id): LiveStroke =
    copy(id = newId, points = points.map { PointF(it.x + dx, it.y + dy) })

/** Minimal stroke surrogate whose `points` decode directly to [PointF] (see [LiveStroke.fromPointsJson]). */
@Serializable
private data class LeanStrokeSurrogate(
    val color: String = LiveStroke.DEFAULT_COLOR,
    val strokeWidth: Float = LiveStroke.DEFAULT_STROKE_WIDTH,
    @Serializable(with = PointFListSerializer::class) val points: List<PointF> = emptyList(),
)

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
