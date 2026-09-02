package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.core.StrokeCodec
import com.symmetricalpalmtree.notesproutsn.extension.Row

/**
 * One `stroke` row → one stroke (arc 22 / X2; shared as `:ext-ink` since arc 23 / Y1) — pure,
 * JVM-tested.
 *
 * **A bad row is a dropped stroke, never a lost page.** Arc 11's whole-page blob had to treat an
 * unreadable byte as an unreadable *page*, because half a decode said nothing about where the rest
 * of the ink began; a row says exactly one stroke. So a malformed geometry blob, a cell of the
 * wrong storage class or a stroke with no points is skipped and counted, and the page loads with
 * everything else on it. An unknown style name reads as `PEN` — the same rule the wire mapping takes.
 *
 * The row shape is the one both consumers' `stroke` tables share:
 * `id, "order", color, width, style, blob`, the blob being [StrokeBlob]'s format B.
 */
object StrokeRows {

    /** Columns: `id, "order", color, width, style, blob`. Null = drop this row. */
    fun decode(row: Row): Pair<Long, Stroke>? = try {
        val id = row.text("id")
        val order = row.long("order")
        val color = row.long("color").toInt()
        val width = row.real("width").toFloat()
        val style = InkWire.styleOf(row.text("style"))
        val points = StrokeCodec.decode(row.blob("blob"))
        if (points.size == 0) {
            null
        } else {
            val list = ArrayList<StrokePoint>(points.size)
            for (i in 0 until points.size) {
                list += StrokePoint(
                    points.x[i],
                    points.y[i],
                    points.pressure?.get(i) ?: 1f,
                    points.tilt?.get(i) ?: 0f,
                    0L,
                )
            }
            order to Stroke(id = id, points = list, color = color, width = width, style = style)
        }
    } catch (e: Exception) {
        null
    }
}

/** A stroke's points as `StrokeCodec` format B — the `.soil`'s own encoding, unchanged; what every
 *  consumer's `putStroke` binds as the row's `blob`. */
object StrokeBlob {
    fun encode(stroke: Stroke): ByteArray {
        val n = stroke.points.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val p = FloatArray(n)
        val t = FloatArray(n)
        for (i in 0 until n) {
            val pt = stroke.points[i]
            x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt
        }
        return StrokeCodec.encode(x, y, p, t)
    }
}
