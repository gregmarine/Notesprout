package com.notesprout.android.data.index

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class IndexRepository(private val dao: ObjectDao) {

    // region Folder operations

    suspend fun createFolder(name: String, parentId: String?): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.FOLDER,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = Json.encodeToString(FolderObject())
        )
        dao.insert(entity)
        return entity
    }

    suspend fun renameFolder(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteFolder(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun getFolder(id: String): ObjectEntity? = dao.getById(id)

    // endregion

    // region Notebook operations

    suspend fun createNotebook(name: String, parentId: String?): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.NOTEBOOK,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = Json.encodeToString(NotebookObject())
        )
        dao.insert(entity)
        return entity
    }

    suspend fun renameNotebook(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteNotebook(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun updateNotebookSnapshot(id: String, snapshot: String?) {
        val entity = dao.getById(id) ?: return
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        dao.update(
            entity.copy(
                data = Json.encodeToString(obj.copy(snapshot = snapshot)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateNotebookPageCount(id: String, pageCount: Int) {
        val entity = dao.getById(id) ?: return
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        dao.update(
            entity.copy(
                data = Json.encodeToString(obj.copy(pageCount = pageCount)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getNotebook(id: String): ObjectEntity? = dao.getById(id)

    // endregion

    // region Navigation operations

    suspend fun getChildren(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = null)

    suspend fun getFolders(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = ObjectType.FOLDER)

    suspend fun getNotebooks(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = ObjectType.NOTEBOOK)

    suspend fun getAllNotebooks(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.NOTEBOOK }

    suspend fun getAllFolders(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.FOLDER }

    // endregion

    // region Object movement

    suspend fun moveObject(id: String, newParentId: String?) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
    }

    // endregion
}
