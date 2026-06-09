package com.notesprout.android

import java.io.File

sealed class NotebookListItem {
    data class Folder(val name: String, val file: File) : NotebookListItem()
    data class Notebook(val file: File) : NotebookListItem()
}
