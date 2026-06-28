package com.notesprout.android

import android.graphics.RectF
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TextRender

object NotesproutClipboard {
    data class ClipboardContent(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val boundingBox: RectF,
        val textObjects: List<TextRender> = emptyList(),
        val lineObjects: List<LineRender> = emptyList(),
        val links: List<LinkRender> = emptyList(),
        val stickyNotes: List<StickyNoteRender> = emptyList(),
        val shapeObjects: List<ShapeRender> = emptyList(),
    )
    var content: ClipboardContent? = null
    fun hasContent(): Boolean = content != null
    fun clear() { content = null }
}
