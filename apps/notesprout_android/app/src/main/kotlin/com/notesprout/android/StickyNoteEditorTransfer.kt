package com.notesprout.android

import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TextRender
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-memory hand-off between [NotebookActivity] and [StickyNoteEditorActivity].
 *
 * [input]  is set by the host before launching the editor.
 * [output] is written by the editor in onPause; read (and cleared) by the host's
 *           editorLauncher callback. null = editor closed without a content change.
 *
 * The singleton does not survive process death, so both sides also carry their half through
 * `onSaveInstanceState` via the codec helpers below: the editor saves its live canvas (restored
 * when [input] is null on recreate), and each host saves its `pendingStickyNote`, so a
 * low-memory kill behind the editor no longer silently discards the whole editing session.
 */
object StickyNoteEditorTransfer {
    @Serializable
    data class Content(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val textObjects: List<TextRender>,
        val lines: List<LineRender>,
        val shapes: List<ShapeRender> = emptyList(),
        val contentWidth: Float,
        val contentHeight: Float,
    )
    var input: Content? = null
    var output: Content? = null

    private val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encodeContent(c: Content): String = codec.encodeToString(Content.serializer(), c)
    fun decodeContent(json: String): Content? =
        runCatching { codec.decodeFromString(Content.serializer(), json) }.getOrNull()

    fun encodeNote(n: StickyNoteRender): String = codec.encodeToString(StickyNoteRender.serializer(), n)
    fun decodeNote(json: String): StickyNoteRender? =
        runCatching { codec.decodeFromString(StickyNoteRender.serializer(), json) }.getOrNull()

    /** Shared instance-state keys for the hosts' pendingStickyNote save/restore. */
    const val STATE_PENDING_NOTE = "pending_sticky_note"
    const val STATE_PENDING_CREATE = "pending_sticky_create"
}
