package com.notesprout.android

/**
 * In-memory hand-off for Scratch Pad → Notebook transfer (S7).
 * Set by ScratchpadActivity before finish(); consumed once by NotebookActivity on result.
 */
object ScratchpadTransfer {
    var pending: NotesproutClipboard.ClipboardContent? = null
}
