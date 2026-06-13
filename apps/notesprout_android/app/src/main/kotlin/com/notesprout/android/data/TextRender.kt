package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Render-time representation of a text object row — NOT stored in the DB.
 * Built at load time from `type = "text"` rows in the notebook table.
 *
 * The markdown source is in [text]; rendering is handled by
 * [com.notesprout.android.core.markdown.TextObjectRenderer] which parses
 * the markdown and draws it via StaticLayout on a transparent background.
 *
 * When [text] is blank and [strokes] is non-null/non-empty, the object is in
 * "unrecognized" state (produced by lasso stroke→text conversion when ML Kit
 * fails to recognise the writing). Canvas render dispatch falls back to drawing
 * the embedded strokes directly instead of the markdown engine.
 *
 * [@Serializable] so text data can be carried in undo/redo actions.
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class TextRender(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val text: String,
    // Embedded original strokes from lasso stroke→text conversion. Null for insert-flow objects.
    val strokes: List<LiveStroke>? = null,
)
