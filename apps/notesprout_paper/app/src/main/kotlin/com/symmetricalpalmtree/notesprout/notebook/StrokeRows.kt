package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesprout.core.InkColorCodec
import com.symmetricalpalmtree.notesprout.core.StrokeCodec
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema

/**
 * The one place a g-paper [Stroke] becomes a `stroke` row and back. The g-paper stroke id **is** the
 * row id; geometry is format B ([StrokeCodec]) with both pressure and tilt channels written; colour
 * goes through [InkColorCodec]; `style` is the [StrokeStyle] name (unknown → PEN). Pure Kotlin —
 * JVM-tested.
 */
object StrokeRows {

    fun toRow(stroke: Stroke, pageId: String, order: Int, now: Long): SoilObjectEntity {
        val n = stroke.points.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val p = FloatArray(n)
        val t = FloatArray(n)
        for (i in 0 until n) {
            val pt = stroke.points[i]
            x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt
        }
        return SoilObjectEntity(
            id = stroke.id, parentId = pageId, type = SoilSchema.TYPE_STROKE, order = order,
            createdAt = now, updatedAt = now,
            color = InkColorCodec.encode(stroke.color), strokeWidth = stroke.width, style = stroke.style.name,
            blob = StrokeCodec.encode(x, y, p, t),
        )
    }

    /** Decode one row; null when the blob is missing or malformed (the caller drops it and moves on). */
    fun toStroke(row: SoilObjectEntity): Stroke? {
        val blob = row.blob ?: return null
        val pts = try { StrokeCodec.decode(blob) } catch (_: Exception) { return null }
        if (pts.size == 0) return null
        val points = ArrayList<StrokePoint>(pts.size)
        for (i in 0 until pts.size) {
            points.add(StrokePoint(
                x = pts.x[i], y = pts.y[i],
                pressure = pts.pressure?.get(i) ?: 1f,
                tilt = pts.tilt?.get(i) ?: 0f,
                timeMillis = 0L,
            ))
        }
        return Stroke(
            id = row.id, points = points,
            color = InkColorCodec.decode(row.color),
            width = row.strokeWidth ?: Stroke.DEFAULT_WIDTH,
            style = styleOf(row.style),
        )
    }

    fun styleOf(name: String?): StrokeStyle =
        name?.let { n -> StrokeStyle.entries.firstOrNull { it.name == n } } ?: StrokeStyle.PEN
}
