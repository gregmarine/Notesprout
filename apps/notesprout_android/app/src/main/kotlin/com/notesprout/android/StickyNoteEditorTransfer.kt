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

    /**
     * How the editor's debounced real-time save reaches disk for a **notebook** sticky.
     *
     * The editor used to open its own Room/SQLCipher connection to the notebook's `.soil` for this.
     * Two connections to one file meant two writers, and WAL allows only one — their transactions
     * collided and killed the app *during ordinary writing* (write in a sticky, return to the
     * notebook, keep writing). Set by [NotebookActivity] before it launches the editor and cleared
     * when it is destroyed; the host then writes on the connection it already holds, so the two
     * saves serialize through Room instead of fighting over a lock.
     *
     * **The crash-safety this replaces is intact.** The point of the real-time save is that content
     * reaches disk *during* editing rather than only at close, because the close callback never runs
     * on process death. Both Activities live in the same process, so process death takes the host
     * too — routing through it loses no window in which persistence was ever possible. Only the
     * connection changes, not the schedule.
     *
     * Null when no notebook host is listening (a scratch-pad or calendar sticky, or the host is
     * gone); those hosts write through the shared [NotesproutIndex] and never had a second
     * connection to begin with.
     */
    @Volatile
    var persistToHost: (suspend (StickyNoteRender) -> Unit)? = null

    private val codec = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encodeNote(n: StickyNoteRender): String = codec.encodeToString(StickyNoteRender.serializer(), n)
    fun decodeNote(json: String): StickyNoteRender? =
        runCatching { codec.decodeFromString(StickyNoteRender.serializer(), json) }.getOrNull()

    /** Shared instance-state keys for the hosts' pendingStickyNote save/restore. */
    const val STATE_PENDING_NOTE = "pending_sticky_note"
    const val STATE_PENDING_CREATE = "pending_sticky_create"
}
