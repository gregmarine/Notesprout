package com.notesprout.android

import com.notesprout.android.data.index.ObjectEntity

sealed class NotebookListItem {
    data class Folder(val entity: ObjectEntity) : NotebookListItem()
    data class Notebook(val entity: ObjectEntity) : NotebookListItem()
}
