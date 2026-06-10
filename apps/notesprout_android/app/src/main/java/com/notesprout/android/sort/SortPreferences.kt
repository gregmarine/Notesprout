package com.notesprout.android.sort

data class SortPreferences(
    val field: SortField = SortField.NAME,
    val order: SortOrder = SortOrder.ASCENDING,
    val folderSort: FolderSort = FolderSort.FOLDERS_FIRST
)
