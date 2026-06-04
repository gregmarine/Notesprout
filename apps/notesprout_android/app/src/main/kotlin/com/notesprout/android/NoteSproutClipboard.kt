package com.notesprout.android

import android.graphics.RectF
import com.notesprout.android.data.LiveStroke

object NoteSproutClipboard {
    data class ClipboardContent(
        val strokes: List<LiveStroke>,
        val boundingBox: RectF,
    )
    var content: ClipboardContent? = null
    fun hasContent(): Boolean = content != null
    fun clear() { content = null }
}
