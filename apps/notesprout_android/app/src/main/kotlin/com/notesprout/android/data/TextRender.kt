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
 * [@Serializable] so text data can be carried in undo/redo actions
 * in future prompts. [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class TextRender(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val text: String,
)
