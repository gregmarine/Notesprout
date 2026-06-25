package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialized payload of a `type = "sticky_note"` row (the `data` column).
 *
 * The note's embedded content travels inside this JSON so copy/cut/paste, page copy,
 * full-notebook export, and clipboard persistence all carry it automatically.
 *
 * Two independent coordinate spaces:
 * - The row's `boundingBox` is the **icon's** fixed-size rectangle on the page.
 * - The embedded content lives in the **content window's own pixel space**, recorded via
 *   [contentWidth]/[contentHeight] (the canvas px size at authoring time).
 *
 * Lines use [EmbeddedLine] (density-independent dp values) so the note round-trips correctly
 * across devices of differing display density, matching the [LinkObject] precedent.
 */
@Serializable
data class StickyNoteObject(
    val strokes: List<LiveStroke> = emptyList(),
    val headings: List<HeadingStroke> = emptyList(),
    val textObjects: List<TextRender> = emptyList(),
    val lines: List<EmbeddedLine> = emptyList(),
    val contentWidth: Float = 0f,
    val contentHeight: Float = 0f,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): StickyNoteObject = Json.decodeFromString(serializer(), json)
    }
}
