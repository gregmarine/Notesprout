package com.notesprout.android.ui

import com.notesprout.android.data.index.ObjectEntity

sealed class DestinationPickerState {
    object None : DestinationPickerState()
    data class CopyNotebook(val source: ObjectEntity) : DestinationPickerState()
    data class MoveNotebook(val source: ObjectEntity) : DestinationPickerState()
    data class CopyFolder(val source: ObjectEntity) : DestinationPickerState()
    data class MoveFolder(val source: ObjectEntity) : DestinationPickerState()
}
