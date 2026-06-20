package com.notesprout.android.data

import android.graphics.PointF
import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Render-time representation of a `type = "link"` row — NOT stored in the DB.
 * Built at load time from `type = "link"` rows in the notebook table.
 *
 * [boundingBox] is the union of every embedded object's box; the chrome (none / underline /
 * dotted box + chevron) is drawn around it. The embedded objects reuse the existing render
 * models ([LiveStroke], [HeadingStroke], [TextRender], [LineRender]) so the view's existing
 * draw helpers paint them with no special-casing.
 *
 * [@Serializable] so link data can ride in undo/redo actions (like [HeadingStroke] / [LineRender]).
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class LinkRender(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val target: LinkTarget,
    val chrome: LinkChrome,
    val strokes: List<LiveStroke> = emptyList(),
    val headings: List<HeadingStroke> = emptyList(),
    val textObjects: List<TextRender> = emptyList(),
    val lines: List<LineRender> = emptyList(),
)

/**
 * Return a copy of this link translated by ([dx], [dy]) — its [boundingBox] and every embedded
 * object's coordinates are shifted. Used for lasso drag-move (preserving [id]) and paste
 * ([newId] = a fresh UUID). Deep-copies all geometry so the source link is untouched.
 */
fun LinkRender.translate(dx: Float, dy: Float, newId: String = id): LinkRender = LinkRender(
    id = newId,
    boundingBox = RectF(boundingBox).apply { offset(dx, dy) },
    target = target,
    chrome = chrome,
    strokes = strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x + dx, it.y + dy) }) },
    headings = headings.map { h ->
        HeadingStroke(
            h.id,
            RectF(h.boundingBox).apply { offset(dx, dy) },
            h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x + dx, it.y + dy) }) },
            recognizedText = h.recognizedText,
            level = h.level,
        )
    },
    textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox).apply { offset(dx, dy) }, t.text, t.strokes) },
    lines = lines.map { l ->
        l.copy(
            boundingBox = RectF(l.boundingBox).apply { offset(dx, dy) },
            startX = l.startX + dx, startY = l.startY + dy,
            endX = l.endX + dx, endY = l.endY + dy,
        )
    },
)

/**
 * Serialize this render-time link back into a [LinkObject] for the `data` column. Embedded lines are
 * captured as density-independent [EmbeddedLine]s for the given [density] (mirrors link creation).
 */
fun LinkRender.toLinkObject(density: Float): LinkObject = LinkObject(
    target = target,
    chrome = chrome,
    strokes = strokes,
    headings = headings,
    textObjects = textObjects,
    lines = lines.map { it.toEmbeddedLine(density) },
)
