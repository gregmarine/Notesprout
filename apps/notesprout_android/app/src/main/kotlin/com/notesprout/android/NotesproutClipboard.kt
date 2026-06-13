package com.notesprout.android

import android.graphics.RectF
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender

object NotesproutClipboard {
    data class ClipboardContent(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val boundingBox: RectF,
        val textObjects: List<TextRender> = emptyList(),
    )
    var content: ClipboardContent? = null
    fun hasContent(): Boolean = content != null
    fun clear() { content = null }
}
