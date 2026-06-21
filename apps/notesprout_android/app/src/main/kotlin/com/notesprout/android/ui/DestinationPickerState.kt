package com.notesprout.android.ui

import com.notesprout.android.ImportManifest
import com.notesprout.android.data.index.ObjectEntity
import java.io.File

sealed class DestinationPickerState {
    object None : DestinationPickerState()
    data class CopyNotebook(val source: ObjectEntity) : DestinationPickerState()
    data class MoveNotebook(val source: ObjectEntity) : DestinationPickerState()
    data class CopyFolder(val source: ObjectEntity) : DestinationPickerState()
    data class MoveFolder(val source: ObjectEntity) : DestinationPickerState()
    /** User chose "Choose folder…" during import — picker round-trip carries all import context. */
    data class ImportNotebook(
        val manifest: ImportManifest,
        val tempFile: File,
        val resolvedId: String,
        val displayName: String,
    ) : DestinationPickerState()
}
