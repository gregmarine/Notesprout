package com.notesprout.android

/**
 * In-memory hand-off for Calendar → Notebook transfer ("Send to Notebook").
 * Set by [CalendarActivity] before finish()/launch; consumed once by [NotebookActivity] either on
 * the for-result return (current notebook) or after the initial page load (other / from-main).
 */
object CalendarTransfer {
    var pending: NotesproutClipboard.ClipboardContent? = null
}
