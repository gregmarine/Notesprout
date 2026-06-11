package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ObjectDao {

    // Two backing queries handle the IS NULL case correctly — Room cannot express
    // "WHERE parentId IS NULL" via a nullable parameter in a single @Query.
    @Query("SELECT * FROM objects WHERE parentId IS NULL AND deletedAt IS NULL")
    suspend fun getChildrenOfRoot(): List<ObjectEntity>

    @Query("SELECT * FROM objects WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun getChildrenOfParent(parentId: String): List<ObjectEntity>

    suspend fun getChildren(parentId: String?, type: String?): List<ObjectEntity> {
        val all = if (parentId == null) getChildrenOfRoot() else getChildrenOfParent(parentId)
        return if (type == null) all else all.filter { it.type == type }
    }

    @Query("SELECT * FROM objects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ObjectEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(obj: ObjectEntity)

    @Update
    suspend fun update(obj: ObjectEntity)

    @Query("UPDATE objects SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("SELECT * FROM objects WHERE deletedAt IS NULL")
    suspend fun getAllNotDeleted(): List<ObjectEntity>
}
