package com.notesprout.android

import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender

/**
 * In-memory hand-off between [NotebookActivity] and [StickyNoteEditorActivity].
 *
 * [input]  is set by the host before launching the editor.
 * [output] is written by the editor in onPause; read (and cleared) by the host's
 *           editorLauncher callback. null = editor closed without a content change.
 */
object StickyNoteEditorTransfer {
    data class Content(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val textObjects: List<TextRender>,
        val lines: List<LineRender>,
        val contentWidth: Float,
        val contentHeight: Float,
    )
    var input: Content? = null
    var output: Content? = null
}
