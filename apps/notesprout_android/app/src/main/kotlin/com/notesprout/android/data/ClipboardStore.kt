package com.notesprout.android.data

import com.notesprout.android.data.index.CLIPBOARD_ID
import com.notesprout.android.data.index.ObjectDao
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType

object ClipboardStore {

    suspend fun write(dao: ObjectDao, payload: ClipboardPayload) {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = CLIPBOARD_ID,
            type = ObjectType.CLIPBOARD,
            name = "clipboard",
            parentId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = payload.toJson(),
        )
        val existing = dao.getById(CLIPBOARD_ID)
        if (existing != null) dao.update(entity) else dao.insert(entity)
    }

    suspend fun read(dao: ObjectDao): ClipboardPayload? {
        val entity = dao.getById(CLIPBOARD_ID) ?: return null
        if (entity.deletedAt != null || entity.data == "{}") return null
        return try { ClipboardPayload.fromJson(entity.data) } catch (_: Exception) { null }
    }

    suspend fun clear(dao: ObjectDao) {
        val existing = dao.getById(CLIPBOARD_ID) ?: return
        if (existing.deletedAt == null) {
            dao.softDelete(CLIPBOARD_ID, System.currentTimeMillis())
        }
    }
}
