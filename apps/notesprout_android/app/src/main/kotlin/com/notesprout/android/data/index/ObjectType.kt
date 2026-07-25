package com.notesprout.android.data.index

object ObjectType {
    const val FOLDER = "folder"
    const val NOTEBOOK = "notebook"
    const val LIST = "list"
    const val TEMPLATE = "template"
    const val TEMPLATE_FOLDER = "template_folder"
    /** A single membership edge: `parentId` = list id, `refId` = member id, `sortOrder` = position. */
    const val LIST_ITEM = "list_item"
    const val CLIPBOARD = "clipboard"
    const val BACKUP_CONFIG = "backup_config"
}
