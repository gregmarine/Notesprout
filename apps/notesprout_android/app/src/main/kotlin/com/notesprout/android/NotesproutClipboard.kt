package com.notesprout.android

import android.graphics.RectF
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke

object NotesproutClipboard {
    data class ClipboardContent(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val boundingBox: RectF,
    )
    var content: ClipboardContent? = null
    fun hasContent(): Boolean = content != null
    fun clear() { content = null }
}
