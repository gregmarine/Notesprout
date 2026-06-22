package com.notesprout.android.data.backup

fun needsBackup(updatedAt: Long, lastBackedUp: Long?, excludeFromBackup: Boolean): Boolean {
    if (excludeFromBackup) return false
    return lastBackedUp == null || updatedAt > lastBackedUp
}
