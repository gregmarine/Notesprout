package com.notesprout.android.ui

import java.io.File

sealed class DestinationPickerState {
    object None : DestinationPickerState()
    data class CopyNotebook(val source: File) : DestinationPickerState()
    data class MoveNotebook(val source: File) : DestinationPickerState()
    data class CopyFolder(val source: File) : DestinationPickerState()
    data class MoveFolder(val source: File) : DestinationPickerState()
}
