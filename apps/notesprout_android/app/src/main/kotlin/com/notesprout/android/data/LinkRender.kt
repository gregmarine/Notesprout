package com.notesprout.android.data

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
