package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Render-time representation of a `type = "sticky_note"` row — NOT stored in the DB.
 * Built at load time from `type = "sticky_note"` rows in the notebook table.
 *
 * [boundingBox] is the **icon's** fixed-size rectangle **on the page** — what lasso hit-tests
 * and moves. The embedded content lives in a separate coordinate space (the content window's
 * own pixel canvas); it does NOT render on the page, only the icon does.
 *
 * [@Serializable] so sticky note data can ride in undo/redo actions (like [LinkRender]).
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class StickyNoteRender(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val strokes: List<LiveStroke> = emptyList(),
    val headings: List<HeadingStroke> = emptyList(),
    val textObjects: List<TextRender> = emptyList(),
    val lines: List<LineRender> = emptyList(),
    val contentWidth: Float = 0f,
    val contentHeight: Float = 0f,
)

/**
 * Return a copy of this sticky note translated by ([dx], [dy]).
 *
 * Only the icon [boundingBox] is offset — the embedded content lives in its own coordinate space
 * and must NOT be translated. Used for lasso drag-move (preserving [id]) and paste ([newId]).
 */
fun StickyNoteRender.translate(dx: Float, dy: Float, newId: String = id): StickyNoteRender = copy(
    id = newId,
    boundingBox = RectF(boundingBox).apply { offset(dx, dy) },
    strokes = strokes,
    headings = headings,
    textObjects = textObjects,
    lines = lines,
)

/**
 * Serialize this render-time sticky note back into a [StickyNoteObject] for the `data` column.
 * Embedded lines are captured as density-independent [EmbeddedLine]s (mirrors [LinkRender.toLinkObject]).
 */
fun StickyNoteRender.toStickyNoteObject(density: Float): StickyNoteObject = StickyNoteObject(
    strokes = strokes,
    headings = headings,
    textObjects = textObjects,
    lines = lines.map { it.toEmbeddedLine(density) },
    contentWidth = contentWidth,
    contentHeight = contentHeight,
)
